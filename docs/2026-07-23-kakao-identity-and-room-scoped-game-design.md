# 카카오 식별자 정규화 + 방 단위 게임 세션 설계

- 날짜: 2026-07-23
- 브랜치: `fix/botKey`
- 상태: 설계 확정, 구현 대기

## 배경

현재 구현은 카카오가 보내는 세 식별자 중 `userRequest.user.id` **하나만** 읽고, 그 값을 `users.app_user_id` 와 `bot_users.bot_user_key` 양쪽에 넣는다(`GameService.startGame` → `UserService.register(appUserId = userId, botUserKey = userId)`). 카카오의 실제 `appUserId` / `botUserKey` 페이로드 필드는 미사용이다.

또한 게임 세션 조회가 `game.bot_key` 컬럼에 `user.id` 를 넣어 키잉하고 있어, 게임이 **채팅방 단위가 아니라 유저 단위**로 스코프돼 있다.

이 설계는 둘을 함께 바로잡는다.

- 세 식별자를 각자의 출처에서 읽고, 서비스 계층에는 non-null 로 확정해 넘긴다.
- 게임 세션을 채팅방(`botKey`) 단위로 바꾼다 — 방 하나당 `PLAYING` 게임 하나.

## 용어

| 이름 | 의미 | 카카오 페이로드 경로 | 매핑 엔티티 |
|---|---|---|---|
| `appUserId` | 카카오 **앱 기준** 전역 유저 키. 같은 사람이면 어느 방에서든 같은 값. | `userRequest.user.properties.appUserId` | `User.appUserId` |
| `botUserKey` | 카카오 **챗봇 기준** 유저 키. 방이 다르면 값이 다를 수 있다. | `userRequest.user.properties.botUserKey` | `BotUser.botUserKey` |
| `botKey` | 채팅방/그룹 식별자. *누구*가 아니라 *어느 방*. | `userRequest.chat.properties.botGroupKey` | `BotUser.botKey`, `Game.botKey` |

## 요구사항

1. 세 식별자를 각자의 실제 페이로드 경로에서 읽는다.
2. 서비스 계층은 세 값을 모두 non-null 로 받는다.
3. **점수·랭킹 노출은 언제나 `appUserId` 로 조회한 `User.score` 기준이다.** 같은 사람이면 방이 달라도 같은 점수가 보여야 한다.
4. 채팅방 하나당 `PLAYING` 게임은 하나다.
5. 어떤 페이로드가 와도 역직렬화 단계에서 500 이 나가면 안 된다(카카오 스킬은 non-200 을 반환하면 안 됨).

## 결정 사항과 근거

### D1. 기존 데이터 이관 — 하지 않는다

`users.app_user_id` 에 `user.id` 가 들어간 기존 행을 실제 `appUserId` 로 백필하는 이관 로직은 만들지 않는다. prod 의 `users`/`bot_users` 데이터는 버려도 되는 것으로 확인했다(소유자 확인, 2026-07-23).

> **주의 — 이관을 건너뛴 대가**: 만약 지킬 데이터가 있었다면 결과는 "옛 점수 유실"이 아니라 **영구적 불일치**다. `getOrCreateUser(appUserId)` 는 새 `User`(score 0)를 만들지만 `getOrCreateBotUser` 는 기존 `BotUser` 를 찾아 그대로 반환하고, `BotUser.user` 는 `val` 이라 재연결되지 않는다. `RankingService` 는 `botUser.user.score`(고아가 된 옛 `User`)를 읽으므로 랭킹엔 멈춘 숫자가, 승리 메시지엔 오르는 숫자가 나온다. 나중에 살아있는 데이터가 있는 환경에 이 변경을 적용한다면 두 테이블을 비우거나 `getOrCreateBotUser` 에서 재연결하는 절차가 반드시 필요하다.

> **스키마 주의**: `ddl-auto` 가 `update` 인 건 `local`/`dev` 뿐이다. **prod 는 `validate`** 이므로(`application-prod.yml:36`) 새 테이블(`season_user_scores`, `season_bot_scores`)의 DDL 을 배포 **전에** 직접 적용해야 한다. 안 하면 기동 실패 → 헬스체크 미달 → `start.sh` 가 이전 jar 로 롤백한다.

### D2. DTO 는 전부 nullable, `ChatIdentity` 에서 non-null 확정

`SkillRequest` 는 페이로드를 있는 그대로 받는다 — `chat`, `properties`, `appUserId`, `botUserKey` 전부 nullable.

DTO 를 non-null 로 못 박으면 값이 빠진 페이로드 한 건에 **Jackson 역직렬화가 실패해 500** 이 나간다. 이건 `SkillExceptionHandler` 가 못 잡는다(핸들러 진입 전 단계) → 방 전체가 먹통. 요구사항 5 위반이다.

대신 `ChatIdentity.from(request)` 가 유일한 non-null 확정 지점이 된다. 폴백 규칙이 한 곳에만 존재하므로 카카오 스펙이 바뀌어도 여기만 고친다.

### D3. `appUserId` 부재는 폴백하지 않고 거부한다

`appUserId` 는 채널에 카카오 앱키가 연동된 경우에만 내려온다(이 저장소는 같은 이유로 랭킹 멘션 타입을 `appUserId` → `botUserKey` 로 되돌린 이력이 있다: `637a480` → `38382b8`).

> **2026-07-23 확인**: 이 채널은 앱키 연동이 되어 있다(소유자 확인). 따라서 아래 거부 경로는 정상 트래픽에서 타지 않으며, 연동이 끊기거나 설정이 바뀌는 경우를 잡는 안전망으로 남는다. `PLAN.md:713-714` 의 "실제 카카오 JSON 로깅으로 `properties.appUserId` 수신 확인" 항목은 여전히 미체크이므로, dev 배포 후 실제 페이로드 1건으로 확정하고 체크하는 것이 좋다.

`appUserId ?: botUserKey` 폴백은 **요구사항 3 을 정면으로 깬다.** 같은 사람이 A 방·B 방에 있는데 appUserId 가 안 오면 `users` 행이 2개로 갈리고 방마다 다른 점수가 노출된다.

따라서 폴백하지 않고 `MissingAppUserIdException` 을 던진다. 게임도 진행하지 않는다.

이 예외 타입은 `IllegalArgumentException` / `IllegalStateException` 중 **어느 것도 아니어야** 한다. `LogTraceAspect` 가 그 둘만 `REJECTED`(정상 유저 흐름)로 분류하고 나머지를 `ERROR` 로 남기기 때문에, 별도 타입이면 CloudWatch `error_count` 알람이 자동으로 붙는다. 앱키 연동이 끊기면 조용히 데이터가 갈리는 게 아니라 알람으로 드러나야 한다.

### D4. `botKey` 부재는 `botUserKey` 로 폴백한다

1:1 채팅에는 `botGroupKey` 가 없다. 이때는 "유저 자신이 방"으로 취급한다. 게임 세션·`BotUser` 가 그대로 성립하고, 요구사항 3 은 `appUserId` 기준이라 영향받지 않는다.

### D5. 시작 충돌 — 강제 교체 + 안내

방에 `PLAYING` 게임이 있는데 누군가 `시작` 을 치면 기존 게임을 `GIVEUP` 처리하고 새 게임을 만든다(현재 동작 유지). 다만 방 단위가 되면서 "남의 게임을 끊는" 행위가 되므로, 응답에 무엇이 종료됐는지 명시한다.

### D6. 유일성은 DB 가 아니라 코드에서 수렴시킨다 (B2)

`UNIQUE(bot_key, status)` 는 **쓸 수 없다.** 그러면 한 방에서 `WON` 게임이 평생 1건만 가능해져 두 번째 승리에서 유니크 위반이 난다. MySQL 에는 부분 유니크 인덱스(`WHERE status='PLAYING'`)가 없다.

DB 로 강제하려면 "PLAYING 일 때만 값이 차는 별도 컬럼"(`active_bot_key` + `UNIQUE`)이 필요한데, 이건 컬럼 하나와 **Hibernate flush 순서 함정**(같은 트랜잭션의 INSERT 가 UPDATE 보다 먼저 실행돼 유니크 위반)을 영구히 코드에 심는다.

유령 게임이 생기는 조건(같은 방에서 두 명이 수십 ms 안에 `시작`)도, 그 결과(다음 `시작` 까지 남는 죽은 행)도 그 비용에 비해 가볍다. 따라서 스키마는 그대로 두고 코드로 수렴시킨다.

- `startGame` 은 방의 `PLAYING` 게임을 **전부** 조회해 전건 `giveUp()` 한다.
- `currentGame` 은 **최신 1건**을 쓴다.

동시 시작으로 유령이 생겨도 다음 `시작` 때 정리되고, 그 사이엔 최신 게임이 정상 동작한다. 불변식은 "DB 보장"이 아니라 "수렴 보장"이다.

> **동시 *추측*은 감수한다(2026-07-23 결정).** 방 단위가 되면서 같은 방의 두 명이 거의 동시에 정답을 낼 수 있는데, `Game` 에 `@Version` 이 없어 둘 다 `check(isPlaying)` 을 통과하고 둘 다 `win()`·`accrue` 할 수 있다. 유저 단위였을 땐 카카오가 유저별 발화를 직렬화해줘서 불가능했던 일이다. 확률이 낮고 피해도 작아(점수 몇 점 중복) 낙관적 락을 걸지 않기로 했다. 되짚을 일이 생기면 `Game` 에 `@Version` 을 얹고 `OptimisticLockException` 을 `SkillExceptionHandler` 에서 200 안내로 변환하면 된다(카카오는 non-200 금지).

### D7. 점수 귀속 — 맞힌 사람

A 가 시작한 게임을 B 가 맞히면 **B** 에게 점수가 간다. `guess` 에 넘어오는 `ChatIdentity` 는 항상 발화자의 것이므로 코드는 그대로지만, 방 단위가 되면서 처음 의미를 갖는 규칙이라 명시한다.

## 설계

### 식별자 추출 계층

`SkillRequest` (DTO) — 페이로드를 있는 그대로:

```kotlin
data class UserRequest(val utterance: String, val user: User, val chat: Chat?)
data class User(val id: String, val properties: UserProperties?)
data class UserProperties(val appUserId: String?, val botUserKey: String?)
data class Chat(val properties: ChatProperties?)
data class ChatProperties(val botGroupKey: String?)
```

`ChatIdentity` (신규, `dto` 패키지) — non-null 확정:

```kotlin
data class ChatIdentity(
    val appUserId: String,
    val botUserKey: String,
    val botKey: String,
) {
    companion object {
        fun from(req: SkillRequest): ChatIdentity { /* 아래 표대로 */ }
    }
}
```

| 값 | 1순위 | 폴백 |
|---|---|---|
| `botUserKey` | `user.properties.botUserKey` | `user.id` (스펙상 `user.type == "botUserKey"` 면 동일 값, 무손실) |
| `botKey` | `chat.properties.botGroupKey` | `botUserKey` (D4) |
| `appUserId` | `user.properties.appUserId` | 없음 → `MissingAppUserIdException` (D3) |

폴백이 발동하면 `evt=identity_fallback` WARN 로그를 1줄 남긴다.

적용처는 두 곳이다: `SkillController.play`, `LogTraceAspect.trace`. aspect 는 현재 `user.id` 를 `botUserKey` 로 직접 읽고 있는데(`LogTraceAspect.kt:39`) 같은 헬퍼로 통일한다. aspect 안에서는 예외가 나면 안 되므로 `MissingAppUserIdException` 을 삼키고 `appUserId="-"` 로 로깅한다(로깅이 요청을 죽이면 안 됨).

### 게임 세션

`Game` 엔티티·스키마는 변경 없음. `bot_key` 컬럼에 이제 **진짜 방 키**가 들어간다. 단 `Game.kt:19` 의 클래스 주석은 `"bot_key 컬럼엔 botKey ?: appUserId 가 들어간다"` 고 적혀 있어 사실과 어긋나게 되므로 방 단위 규칙으로 고쳐 쓴다.

`GameRepository`:

```kotlin
fun findAllByBotKeyAndStatus(botKey: String, status: GameStatus): List<Game>
fun findFirstByBotKeyAndStatusOrderByIdDesc(botKey: String, status: GameStatus): Game?
```

기존 `findFirstByBotKeyAndStatus` 는 제거한다. `idx_game_bot_key_status` 는 두 쿼리 모두가 쓰므로 유지한다.

`GameService`:

```kotlin
fun startGame(id: ChatIdentity, difficulty: GameDifficulty = NORMAL): StartOutcome
fun guess(id: ChatIdentity, guess: String): GuessOutcome
fun giveUp(id: ChatIdentity): String
```

`startGame` 흐름:

1. `userService.register(id)`
2. `findAllByBotKeyAndStatus(id.botKey, PLAYING)` → 전건 `giveUp()`
3. 새 `Game(botKey = id.botKey, ...)` 저장
4. `StartOutcome(game, replacedAnswer = 교체된 게임 중 최신 1건의 answer 또는 null)` 반환

반환 타입이 `Game` → `StartOutcome` 으로 바뀌는 이유는 교체된 게임의 정답을 안내 문구에 넣기 위해서다.

`currentGame(botKey)` 은 `findFirstByBotKeyAndStatusOrderByIdDesc` 를 쓴다.

`giveUp` 의 미사용 `appUserId` 파라미터는 사라진다(`ChatIdentity` 하나만 받음).

### 서비스 계층 null 분기 제거

`ChatIdentity` 가 non-null 을 보장하므로 아래가 전부 삭제된다.

- `UserService.kt:72`, `:89` 의 `if (botKey != null)` 분기 2개
- `SkillController.kt:250` 의 랭킹 `botKey == null` 안내 분기
- `UserService.register(appUserId, botKey, botUserKey)` 의 인자 순서 함정 — 시그니처가 `register(id: ChatIdentity)` 가 되면서 사라짐

```kotlin
UserService.register(id: ChatIdentity)
UserService.accrue(id: ChatIdentity, gain: Int): Int
```

`RankingService.getBotRanking(botKey: String)` 은 이미 non-null 이라 변경 없음.

### 응답

- **시작 카드**: `replacedAnswer != null` 이면 설명 첫 줄에 `"진행 중이던 게임(정답 1234)을 종료했어요."` 를 붙인다. 230자 캡은 `cardOrText` 의 `.take()` 가 이미 방어한다.
- **`MissingAppUserIdException`**: `SkillExceptionHandler` 에 핸들러를 추가해 200 + `"일시적인 문제가 발생했어요. 잠시 후 다시 시도해주세요."` 로 변환한다.
- 그 외 문구·카드 타입은 변경 없음.

## 테스트

현재 브랜치는 **117개 중 13개 실패** 상태다(`SkillControllerIntegrationTest` 대부분 + `GameServiceTest` 일부). 전부 복구한다.

신규:

- **`ChatIdentityTest`** — 프레임워크 없는 순수 단위 테스트(기존 pure-core 계층 규약을 따름)
  - `properties` 값이 있으면 그걸 쓴다
  - `properties.botUserKey` 가 없으면 `user.id` 로 폴백
  - `chat` / `botGroupKey` 가 없으면 `botUserKey` 를 `botKey` 로
  - `appUserId` 가 없으면 `MissingAppUserIdException`
- **`GameServiceTest`** — 방에 `PLAYING` 이 2건이면 전부 `giveUp` 되고 최신 1건이 `currentGame` 으로 잡힌다
- **통합 테스트**
  - A 가 시작 → B 가 시작하면 A 의 게임이 `GIVEUP` 이고 응답에 교체 안내가 포함된다
  - A 가 시작 → B 가 맞히면 **B 의 점수만** 증가한다 (D7)
  - `appUserId` 없는 페이로드 → 200 안내 응답 + 게임이 생성되지 않는다

`SkillControllerCardTest` 는 `GameService` 를 목으로 쓰므로 `startGame` 의 `StartOutcome` 반환·`ChatIdentity` 파라미터 변경에 맞춰 스텁을 갱신한다(카드 검증 자체는 그대로).

`SkillControllerIntegrationTest.body()` 헬퍼를 실제 페이로드 형태(`user.properties` 포함)로 교체한다. `"랭킹: bot 정보가 없으면 안내 메시지"` 테스트는 `botKey` 가 항상 존재하게 되어 의미가 사라지므로 삭제한다.

### 로컬 실행 메모

이 환경의 기본 JDK 는 26 이고 Gradle 8.10 이 지원하지 않아 `./gradlew test` 가 `* What went wrong: 26.0.1` 로 즉시 실패한다. JDK 17 또는 21 로 실행해야 한다:

```
JAVA_HOME=<corretto-17 또는 21 경로> ./gradlew test
```

## CLAUDE.md 갱신

- `"Key domain quirk: Game.botKey actually holds userId"` 섹션 삭제 — 이번 변경으로 사실이 아니게 된다. 방 단위 게임 규칙(D5, D6, D7)으로 대체한다.
- 식별자 섹션의 `"중요 — 현재 구현은 위 두 카카오 필드를 실제로 읽지 않는다"` 문단 삭제.
- `ChatIdentity` 를 식별자 정규화의 단일 지점으로 명시하고, D3(폴백 금지 + 전용 예외)의 근거를 남긴다.

## 범위 밖

- 기존 prod 데이터 이관 (D1)
- 랭킹 멘션 타입 변경 — `botUserKey` 유지. 앱키 연동 확인 없이 건드리지 않는다.
- 시즌 스냅샷(`season_bot_scores`) 로직 — `(bot_key, bot_user_key)` grain 은 그대로 유효하다.
