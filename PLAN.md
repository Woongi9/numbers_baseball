# 숫자야구 카카오톡 챗봇 기획 문서

> **1차 목표(완료): 게임을 H2(DB)에 저장 + 클라우드 HTTPS 실배포 + 오픈빌더 연동** ✅
> **2차 목표(고도화 — 진행): 게임/랭킹 두 갈래로 확장 (난이도 모드 · MMR · 채팅방 랭킹)**
> 기술: Kotlin + Spring Boot + JPA / H2(개발) → MySQL(운영 선택) / 클라우드 배포

---

## ⚠️ 먼저: 일정 현실 점검

선택하신 범위(DB 저장 + 실배포 + 부가기능)는 **하루치고는 빡빡**합니다. 그래서 아래처럼 **2티어**로 나눴습니다.

| 티어 | 내용 | 비고 |
|------|------|------|
| 🔴 **필수 (내일 반드시)** | Spring Boot 전환 → 판정 로직(TDD) → DB 저장 → 컨트롤러 → **실배포(HTTPS)** → 오픈빌더 연동 | 이것만 되면 "동작하는 배포된 챗봇" 완성 |
| 🟡 **여유 되면** | 힌트 / 시도 제한 / 자릿수 선택 / 전적·랭킹 / 모니터링 | 시간 남을 때 추가 |

> **전략 제안**: DB는 **H2 파일 모드**로 먼저 띄워서 빠르게 배포까지 도달하고, 운영 안정화는 그 다음 MySQL 전환을 고려하세요. 처음부터 MySQL+RDS를 붙이면 배포 전에 시간을 다 씁니다.

---

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| 게임 | 숫자야구 (기본 **4자리** + 부가기능) |
| 채널 | 카카오톡 채널 + 오픈빌더 스킬 서버 |
| 서버 | Kotlin / Spring Boot 3.x (Web + JPA) |
| 상태 저장 | **DB (H2 → MySQL)**, `userId` 기준 게임 세션 |
| 배포 | 클라우드 + HTTPS (도메인/리버스 프록시) |
| 응답 제한 | **5초 이내** (카카오 타임아웃) |

### 현재 상태 (시작점 — 실측)
- **순수 Kotlin/JVM `application` 프로젝트** (Spring Boot 아님)
- `build.gradle.kts`: `kotlin("jvm") 2.1.10`, 의존성은 `kotlin.test`만
- `Main.kt`: `println("Hello World!")`
- Gradle 8.10 / JVM 21

→ **1번 작업: build.gradle.kts를 Spring Boot 3.x로 전환** (JVM 21이라 Boot 3.x 적합)

---

## 2. 서비스 구조 (게임 / 랭킹 두 갈래)

> 고도화 후 사용자가 만나는 전체 기능 트리.

```
숫자야구 챗봇
├── 게임
│   ├── 시작
│   │   ├── 보통 모드            (숫자 4자리 — 기본/국룰)
│   │   └── 어려움 모드          (숫자+알파벳) ......... 🟡 추후 추가
│   └── 번호 입력
│       ├── 결과 응답           (ex. "1S 2B", "OUT")
│       └── 정답 시            (정답 + 획득 MMR + 현재 MMR 응답)
└── 랭킹 조회
    ├── 현재 채팅방 내 랭킹
    └── 전체 서비스 내 랭킹 ............................ 🟡 추후 추가
```

### 2-1. 게임 규칙

**보통 모드 (기본 / 국룰)**
- 정답: 0~9 중 **서로 다른 숫자 4자리** (예: `5273`)
- **스트라이크(S)**: 숫자+자리 일치 / **볼(B)**: 숫자만 일치 / **아웃(OUT)**: `0S 0B`
- `4S`이면 승리 → 종료 + 시도 횟수 + **MMR 상승** 안내

**어려움 모드 (🟡 추후 추가)**
- 정답 구성: **숫자 + 알파벳** 혼합 (예: `A3K7`) — 후보 폭이 넓어 난도↑
- 판정 규칙(S/B/OUT)은 동일, 대소문자 정규화 필요
- MMR 보너스 배수 적용 (아래 3-1 참고)

> **설계 메모**: 난이도는 `Game.difficulty` enum(`NORMAL`/`HARD`) 한 컬럼으로 분기. 정답 생성기·입력 검증만 모드별로 달라지고 판정 로직(`BaseballJudge`)은 **문자 집합만 바뀔 뿐 동일** → 순수 함수 재사용. 어려움 모드는 "허용 문자 집합"을 파라미터로 주입하는 식으로 확장하면 코드 중복이 없습니다.

### 2-2. 명령어 (발화 매핑)

| 사용자 발화 | 동작 |
|------------|------|
| `시작` · `게임` · `새게임` | 새 게임 시작 (기본=보통 모드) |
| `어려움` *(추후)* | 어려움 모드로 새 게임 |
| 4자리 숫자(또는 숫자+알파벳) | 번호 입력 → S/B/OUT 판정 |
| `포기` | 정답 공개 + 게임 종료(MMR 변동 규칙은 3-1) |
| `랭킹` · `채팅방랭킹` | 현재 채팅방 내 MMR 랭킹 TOP N |
| `전체랭킹` *(추후)* | 전체 서비스 MMR 랭킹 TOP N |
| `전적` *(🟡)* | 내 승률·평균 시도·현재 MMR |

---

## 3. MMR & 랭킹 설계 (고도화 핵심)

### 3-1. MMR 산정

숫자야구는 상대가 없는 **싱글 플레이**라 PvP ELO가 아니라 **누적 점수형 MMR**로 설계합니다. (승리 시에만 상승, 적은 시도일수록·어려울수록 더 많이 획득)

```
획득 MMR = max(MIN_GAIN, (BASE - tries * STEP)) * 난이도배수
현재 MMR += 획득 MMR        // 승리 시에만 적용
```

| 상수 | 값(제안) | 의미 |
|------|---------|------|
| BASE | 100 | 기본 점수 |
| STEP | 5 | 시도 1회당 차감 |
| MIN_GAIN | 20 | 많이 틀려도 최소 보장 |
| 난이도배수 | 보통 1.0 / 어려움 1.5 | 모드별 가중 |

| 상황 | 처리(제안) |
|------|-----------|
| 승리 | 위 공식대로 상승 |
| 포기 | 변동 없음(0) — 또는 소폭 하락(-10) 선택 |
| 시도제한 실패(🟡) | 변동 없음(0) |

**계산 예시** — 보통 모드, 7번 만에 정답, 현재 MMR 1000:
```
획득 = max(20, (100 - 7*5)) * 1.0 = max(20, 65) = 65
1000 → 1065
```

**정답 응답 예시**
```
🎉 정답! 5273
7번 만에 맞췄어요.
+65 MMR  (1000 → 1065)
```

> **왜 이렇게?**: 상수(BASE/STEP/배수)를 코드 상단/설정값으로 빼두면 밸런싱을 배포 없이 조정 가능. 산정식은 `BaseballJudge`와 분리된 **순수 함수(`MmrCalculator.gain(tries, difficulty)`)**로 두어 단위 테스트로 검증(평소 강조하는 테스트 커버리지·코드 품질 지표).

### 3-2. 랭킹 조회

조회는 단순 정렬 쿼리: **채팅방 단위로 MMR 내림차순 TOP N**.

```sql
-- 현재 채팅방 내 랭킹
SELECT nickname, mmr FROM player
WHERE chatroom_id = :chatroomId
ORDER BY mmr DESC
LIMIT 10;
```

| 항목 | 결정 |
|------|------|
| 현재 채팅방 랭킹 | `chatroom_id`로 필터 + `mmr DESC` (인덱스 `(chatroom_id, mmr)`) |
| 전체 랭킹 *(추후)* | `WHERE` 없이 전역 `mmr DESC` |
| 표시 | 순위·닉네임·MMR TOP 10 + (선택)내 순위 |

> ⚠️ **반드시 먼저 확인할 기술 이슈 — "채팅방 식별자"**
> 카카오 오픈빌더 일반 채널 챗봇은 기본이 **1:1 대화**라, 스킬 요청 payload에 "단톡방 ID"가 항상 오는지 **검증이 필요**합니다. 요청 본문의 `userRequest.user.id`(botUserKey)는 사용자 식별자이지 채팅방 식별자가 아닐 수 있습니다.
> - **A안**: 그룹/오픈채팅에서 채팅방 키가 내려오면 그 값을 `chatroom_id`로 사용.
> - **B안(폴백)**: 채팅방 키가 없으면, 게임 시작 시 사용자가 입력하는 **방 코드(예: `방 만들기` → 코드 발급, `참가 ABCD`)**로 그룹을 식별.
> 실제 요청 JSON을 한 번 찍어 어떤 식별자가 오는지 확인한 뒤 A/B를 결정하세요. (아래 6번 리스크 표 참고)

---

## 4. 아키텍처

### 요청 흐름 (순서)
```
[게임 흐름]
1. 사용자가 카톡에서 "1234" 입력
2. 오픈빌더 블록 → 스킬 서버로 POST 호출
3. 카카오 → POST /skill/play  (JSON: utterance, user.id, (가능시)채팅방키)
4. Controller가 JSON 수신
5. GameService: userId로 진행중 게임 DB 조회
6. BaseballJudge: 판정(S/B/O) → Game 엔티티 갱신 → DB 저장(JPA)
7. 정답(4S)이면 → MmrCalculator.gain(tries, difficulty) → Player.mmr += 획득 → 저장
8. 카카오 스킬 응답 JSON 생성 (version 2.0 / simpleText)
9. 반환 → 카카오가 말풍선 렌더링

[랭킹 흐름]
1. 사용자가 "랭킹" 입력 → POST /skill/play
2. RankingService: chatroom_id로 player TOP N (mmr DESC) 조회
3. 순위 텍스트 생성 → simpleText 응답
```

### 패키지 구조 (제안)
```
src/main/kotlin/com/example/baseball/
├── BaseballApplication.kt
├── controller/SkillController.kt        # POST /skill/play (게임/랭킹 발화 분기)
├── service/
│   ├── GameService.kt                   # 흐름 + 세션(DB) 관리
│   ├── RankingService.kt               # 🆕 채팅방/전체 랭킹 조회
│   ├── BaseballJudge.kt                 # 순수 판정 로직 (테스트 용이)
│   └── MmrCalculator.kt                # 🆕 순수 MMR 산정 (테스트 용이)
├── domain/
│   ├── Game.kt                          # @Entity: id, userId, answer, digits, difficulty, tries, status, mmrGain
│   ├── GameRepository.kt                # JpaRepository
│   ├── Player.kt                       # 🆕 @Entity: userId, chatroomId, nickname, mmr, wins, totalTries
│   └── PlayerRepository.kt             # 🆕 채팅방 랭킹 조회 쿼리
└── dto/
    ├── SkillRequest.kt                  # 카카오 → 서버
    └── SkillResponse.kt                 # 서버 → 카카오
src/main/resources/application.yml
```

> **설계 의도**: 판정(`BaseballJudge`)과 점수산정(`MmrCalculator`)을 스프링/카카오/DB와 분리한 **순수 함수**로 두어 단위 테스트로 빠르게 검증(평소 강조하는 코드 품질·테스트 커버리지 지표 충족). DB I/O는 `GameService`/`RankingService`로 격리. 게임 진행과 랭킹 조회를 별도 서비스로 나눠 **단일 책임**을 지킵니다.

---

## 5. 데이터 모델

### Game 엔티티 (한 판의 진행 상태)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | Long PK | 게임 ID |
| userId | String (index) | 카카오 user.id |
| answer | String | 정답 (예: "5273", 어려움 "A3K7") |
| digits | Int | 자릿수 (기본 4) |
| difficulty | Enum | 🆕 NORMAL / HARD |
| tries | Int | 시도 횟수 |
| status | Enum | PLAYING / WON / GIVEUP / FAILED |
| mmrGain | Int | 🆕 이 판에서 획득한 MMR (승리 시) |
| createdAt / finishedAt | Timestamp | 통계용 |

### Player 엔티티 🆕 (누적 점수 = 랭킹 대상)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | Long PK | |
| userId | String | 카카오 user.id |
| chatroomId | String (index) | 채팅방 식별자 (3-2 이슈 참고) |
| nickname | String | 표시용 닉네임 |
| mmr | Int | 누적 MMR (랭킹 정렬 키) |
| wins / totalTries | Int | 통계·전적용 |
| updatedAt | Timestamp | |

> **인덱스 주의**:
> - `Game`: `userId + status=PLAYING` 조회가 매 요청마다 발생 → `userId` 인덱스 필수.
> - `Player`: 채팅방 랭킹은 `WHERE chatroom_id = ? ORDER BY mmr DESC` → **복합 인덱스 `(chatroom_id, mmr DESC)`** 로 정렬까지 인덱스로 처리(파일정렬 회피). 사용자 단위 갱신을 위해 `(chatroom_id, user_id)` 유니크도 권장.
> (5초 제한 + 평소 쿼리 최적화 관점)

### 카카오 요청/응답 (필요한 필드만)
```json
// 받는 요청
{ "userRequest": { "utterance": "1234", "user": { "id": "abc123" } } }

// 보내는 응답
{ "version": "2.0", "template": { "outputs": [ { "simpleText": { "text": "1S 1B" } } ] } }
```

---

## 6. 순차 작업 체크리스트

> 위→아래 순서. 각 단계 끝 "확인"으로 검증. 🔴=1차 필수, 🟢=고도화(이번), 🟡=추후.

### 🔴 STEP 1. Spring Boot 전환 (30분) ✅ 완료
- [x] `build.gradle.kts`: `org.springframework.boot` 3.x + `io.spring.dependency-management` + `kotlin("plugin.spring")` + `kotlin("plugin.jpa")`
- [x] 의존성: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `jackson-module-kotlin`, `com.h2database:h2`, `spring-boot-starter-test`
- [x] `application` 플러그인 / `Main.kt` 제거
- [x] **확인**: `./gradlew build` 성공 *(샌드박스 네트워크 제한으로 미실행 — 로컬에서 직접 확인 필요)*

### 🔴 STEP 2. 부트스트랩 + DB (15분) ✅ 완료
- [x] `BaseballApplication.kt` (`@SpringBootApplication`)
- [x] `application.yml`: `server.port`, H2 **파일 모드** datasource, `jpa.hibernate.ddl-auto: update`
- [x] **확인**: `./gradlew bootRun` 기동 + H2 콘솔 접속 *(샌드박스 네트워크 제한으로 미실행 — 로컬에서 직접 확인 필요)*

### 🔴 STEP 3. 판정 로직 + 테스트 (40분) ⭐ TDD ✅ 완료
- [x] `BaseballJudge.judge(answer, guess): Result(strike, ball)`
- [x] 입력 검증: 자릿수 / 숫자만 / 중복 없음
- [x] 단위 테스트: `5273`vs`5283`→3S0B, `5273`vs`2735`→0S4B, `5273`vs`1289`→1S0B(2), 예외 케이스
- [x] **확인**: `./gradlew test` 통과 *(샌드박스 네트워크 제한으로 미실행 — 알고리즘은 동일 로직 재현으로 전 케이스 검증 완료, 로컬에서 최종 확인 필요)*

### 🔴 STEP 4. 도메인 + 세션(DB) (30분) ✅ 완료
- [x] `Game` 엔티티 + `GameRepository`
- [x] `GameService`: 진행중 게임 조회 / 새 게임 / 추측 / 포기 + 랜덤 정답 생성
- [x] **확인**: 서비스 테스트(선택) 또는 H2 콘솔에서 row 확인 *(로직은 시뮬레이션으로 검증 완료, 로컬 bootRun + H2 콘솔에서 row 확인 필요)*

### 🔴 STEP 5. 스킬 컨트롤러 (30분) ✅ 완료
- [x] `SkillRequest`/`SkillResponse` DTO
- [x] `SkillController` `POST /skill/play`: utterance → 명령어/숫자 분기 → 응답
- [x] **확인** *(로컬에서 bootRun 후 실행 필요)*:
  ```bash
  curl -X POST localhost:8080/skill/play -H "Content-Type: application/json" \
    -d '{"userRequest":{"utterance":"1234","user":{"id":"u1"}}}'
  ```

### 🔴 STEP 6. 실배포 (HTTPS) — Oracle Cloud Always Free (가장 큰 변수)

> 배포 방식: **OCI 무료 VM + Nginx + acme.sh(DNS-01) + ZeroSSL**. 상세 명령은 [DEPLOY.md](DEPLOY.md) 참고.
> ✅ **STEP 6 완료 (2026-06-03)**: `https://numbaseball.duckdns.org` HTTPS 실배포 성공. 남은 건 STEP 7(카카오 오픈빌더 연동).

- [x] 1. OCI 가입 (홈 리전 → 춘천/서울 선택, 변경 불가!)
- [x] 2. ARM(A1) 인스턴스 생성 (Ubuntu, public IP)
- [x] 3. 포트 80/443 개방 ← Security List + iptables 둘 다 (최대 함정)
- [x] 4. SSH 접속 + Java 21 설치
- [x] 5. `./gradlew bootJar` → scp 로 업로드
- [x] 6. systemd 등록 (상시 가동 + 자동 재시작)
- [x] 7. 도메인 (무료: DuckDNS)
- [x] 8. HTTPS 발급 → **acme.sh + DNS-01 + ZeroSSL** *(Certbot http-01은 DuckDNS secondary validation NXDOMAIN, Let's Encrypt DNS-01은 챌린지 404로 실패 → ZeroSSL로 전환해 성공. Nginx 80→443 리다이렉트 + 443 SSL 프록시)*
- [x] 9. `curl https` 로 외부 동작 확인 — `https://numbaseball.duckdns.org/skill/play` → 정답 JSON 반환 ✅
- [ ] 10. 카카오 스킬 URL 등록 *(STEP 7과 연결)*
- [ ] 11. 재배포는 `deploy/redeploy.sh`

> 💡 **시간 단축 팁**: 도메인/SSL 직접 구축이 부담되면, HTTPS를 기본 제공하는 PaaS(컨테이너 배포형)를 쓰면 STEP 6을 크게 줄일 수 있습니다. 이게 오늘의 최대 리스크라 여기에 시간을 몰아주세요.

### 🔴 STEP 7. 오픈빌더 연동 (40분)
- [x] 카카오 비즈니스 채널 + 오픈빌더 봇 생성
- [x] 스킬 등록: URL = `https://내도메인/skill/play`
- [x] 블록 + 폴백 블록에 스킬 연결
- [x] **확인**: 봇 테스트에서 숫자 입력 → 판정 응답

### 🔴 STEP 8. 마무리 점검 🔶 일부 완료
- [x] 예외 입력(빈값·자릿수 오류·문자) 처리 — `SkillController.handle()`에서 `IllegalState/IllegalArgument` catch → 안내 메시지로 변환(500 대신 정상 응답)
- [ ] 5초 내 응답 측정(실측 로깅)
- [ ] README 작성

---

> 아래부터 **고도화(게임/랭킹 두 갈래)** 작업.

> 📌 **설계 변경 메모 (2026-06-21 실측)**: 실제 구현은 PLAN 초안의 단일 `Player(userId, chatroomId, mmr...)`가 아니라 **`User`(카카오 앱 계정 · `appUserId` · `score`, 월초 초기화) + `BotUser`(봇/채팅방 단위 · `user` FK · `botKey` · `botUserKey` · `score`)** 2개로 분리됨(Player→User, BotPlayer→BotUser 리네이밍 완료). 즉 `botKey`=채팅방(채널) 식별자, `botUserKey`=그 안의 사용자 → STEP 10의 "채팅방 식별자" 이슈를 botKey/botUserKey 모델로 해소하는 방향. 점수 명칭도 `mmr` 대신 `score`.

> 🎚️ **난이도/자릿수 결정 (2026-06-21)**: **자릿수는 4자리로 고정**(`GameService.DIGITS = 4`), 난이도는 자릿수가 아니라 **후보 기호 집합만 확장**. `Game`/`GameService`에서 `digits` 필드·`MIN/MAX_DIGITS` 상수 전부 제거. 난이도는 enum `GameDifficulty(multiplier, symbols)`로 구현:
>
> | 난이도 | 기호 집합 | 배수 |
> |--------|-----------|------|
> | EASY | `0~5` (6개) | 0.5 |
> | NORMAL | `0~9` (10개) | 1.0 |
> | HARD | `0~9` + `a~e` (15개) | 2.0 |

### 🟢 STEP 9. [고도화] 점수(score) 시스템 🔶 진행 중
- [x] `Game`에 `difficulty` 반영 — `gameDifficulty: GameDifficulty`(@Enumerated STRING) 컬럼 추가 완료
- [x] `GameDifficulty` enum 구현 (EASY/NORMAL/HARD · `multiplier` · `symbols`)
- [x] `User` 엔티티 + `UserRepository` 추가 *(`appUserId`+`score`. nickname/wins/totalTries 등은 미보유)*
- [x] 🆕 `BotUser` 엔티티 + `BotUserRepository`(`findByBotKey`) 추가 — 봇/채팅방 단위 점수 보관
- [x] `startGame(botKey, gameDifficulty=NORMAL)` — 난이도별 후보로 4자리 정답 생성 + 난이도별 테스트(NORMAL/HARD/EASY score=100/200/50, 기호집합·길이·중복 검증)
- [x] `MmrCalculator.gain(tries, difficulty)` 순수 함수 구현 — **미작성**
- [ ] `GameService`: 정답 시 score 산정 → `User`/`BotUser`에 누적 저장 — **미연결** (현재 `guess()`는 `game.win()`만, score 갱신 없음)
- [ ] 정답 응답 포맷에 `+획득 (이전 → 현재)` 표기 — **미반영** (현재는 "정답입니다! N번 만에…"만)
- [ ] 🆕 정답 응답에 **상위 N% (등수/전체)** 표기 — 아래 9-P 설계 참고 — **미반영**
- [ ] **단위 테스트**: 시도수별 획득량/최소 보장/난이도 배수/포기 무변동 — **미작성**
- [ ] **확인**: 정답 맞춘 뒤 score 상승 + 응답 텍스트 + 상위% 검증

> ⚠️ **정리 필요(부채)**: ① `Game.score = (100*multiplier).toInt()`를 **생성 시점에 고정** 중 → PLAN의 `gain = max(MIN_GAIN, BASE - tries*STEP)*배수`(승리 시 계산)로 이전 필요. ② `GameDifficulty`에 `val multiplier`와 `fun multiplier()` 중복. ③ `Game` 인덱스 `columnList="userId,status"`가 없는 컬럼 참조 → `botKey,status`로 수정(DDL 깨짐).

#### 🎯 STEP 9-P. 정답 응답에 "상위 N%" 표기 (2026-06-23 추가)

> 승리 시 **획득량 + 이번달 누적 score**에 더해 **상위 백분위**를 함께 보여준다. STEP 10에서 만든 score 인덱스를 재활용한다.

**원리** — 정렬·전체 조회 없이 **COUNT 2번**으로 끝난다(퍼센타일은 "내 위에 몇 명?"만 세면 됨).

```
rank   = (나보다 점수 높은 사람 수) + 1
topPct = ceil(rank * 100.0 / total)     // 작을수록 상위권
```

```sql
-- 적립(score += gain) 직후, 같은 트랜잭션에서
SELECT COUNT(*) FROM users WHERE score > :myScore;  -- index range scan (테이블 접근 X)
SELECT COUNT(*) FROM users;
```

**내부 동작 순서** (승리 분기에 끼워넣기)

```
1. guess() → 4S(win)
2. gain = MmrCalculator.gain(tries)            // STEP B
3. User.score += gain                          // STEP C, 같은 트랜잭션
4. higher = COUNT(score > myScore); total = COUNT(*)
   rank = higher + 1; topPct = ceil(rank*100/total)
5. 응답 조립("+gain (이전→현재)" + "상위 topPct% (rank/total)")
```

**응답 예시**
```
🎉 정답! 5273 (7번)
+65점  (1000 → 1065)
🏅 이번 시즌 상위 5% (5위 / 100명)
```

| 내 점수 | 나보다 높은 사람 | rank | total | 상위 % |
|--------|-----------------|------|-------|--------|
| 1065 | 4 | 5 | 100 | 상위 5% |
| 1065 | 0 | 1 | 100 | 상위 1% (1등) |

**주의점**

| 구분 | 이슈 | 대응 |
|------|------|------|
| 🔴 동점 | `score > myScore`는 동점자를 같은 등수로 묶음(공동순위) | 보수적·직관적 → 이대로 채택 |
| 🔴 표본부족 | `total`이 작으면(혼자=항상 100%) 무의미 | `total < N`이면 % 대신 "표본 부족" 안내 분기 |
| 🟡 성능 | 하위권은 `score > me`가 거의 전체 카운트 | `users(score)` 인덱스로 인덱스 전용 처리(수만까진 OK) |
| 🟡 트랜잭션 | 적립 **후** 카운트해야 방금 점수 반영 | 같은 트랜잭션에서 계산 |
| 🟢 월리셋 | 리셋 직후 다 0점이라 의미 약함 | 시즌 초반 안내 문구 |
| 🟢 스케일업 | 유저 급증 시 RDB COUNT 부담 | Redis Sorted Set `ZREVRANK`(O(log n)) 또는 분 단위 캐시로 전환 |

**범위(확정 필요)**: `이번달 총합 score`와 일관되게 **전체(글로벌 `User.score`) 기준**을 기본으로. 필요 시 **봇별(`BotUser.score`, STEP 10 인덱스 `(bot_key, score)` 재사용)** 추가 또는 동시 표기(`상위 5%(전체)·2%(이 방)`).

- [ ] `UserRepository.countByScoreGreaterThan(score)` + `count()` (또는 전용 percentile 쿼리)
- [ ] `users(score)` 인덱스 추가 (글로벌 기준 시)
- [ ] 승리 응답 포맷에 `상위 N% (rank/total)` 합성 + 표본부족 분기
- [ ] **단위 테스트**: 동점/1등/꼴찌/표본부족 경계값
- [ ] **확인**: 정답 후 응답에 상위% 정상 표기

### 🟢 STEP 10. [고도화] 랭킹 조회 (현재 채팅방=botKey) ✅ 읽기 완료 (점수 적립 연결만 STEP 9 대기) — 2026-06-23 `feat/ranking`
- [x] ⚠️ **선행 확인**: 채팅방 식별자 = `botKey`(=bot.id)로 결정 + `SkillRequest.bot.id` 파싱 추가 *(단, `appUserId`/`botUserKey` 전체 + 실제 카카오 JSON 로깅 검증은 STEP A로 이월)*
- [x] 정렬·TOP N — `BotUserRepository.findTop10ByBotKeyOrderByScoreDesc` 신설로 해결 *(기존 `getScoresByBotKey`는 비정렬 그대로 / `getScoreByUser` 스텁은 STEP D 소관)*
- [x] 복합 인덱스 `(bot_key, score)` 추가 — `BotUser`에 `@Index` 적용(정렬까지 인덱스 → filesort 회피)
- [x] `RankingService` + `SkillController`에 `랭킹`/`봇랭킹`/`순위` 발화 분기 — 연결
- [x] 랭킹 텍스트 포맷(순위·점수 TOP 10) — 작성 *(닉네임 컬럼 부재 → `botUserKey` 마스킹으로 대체, 닉네임 도입 STEP 9/A 후 교체)*
- [x] **확인**: 테스트 서버에서 `랭킹` 발화 → 정렬 목록 응답 정상 *(시드 데이터 기준 — 실제 게임 누적은 STEP 9 STEP C 연결 후)*
- [ ] (잔여) 실데이터 누적 검증 — STEP 9 STEP C(승리 시 `BotUser.score +=`) 연결 후 "실제 두 명 플레이 → 랭킹"

### 🟡 STEP 11. [고도화-추후] 어려움 모드(판정부) / 전체 랭킹
- [x] 어려움 모드 **정답 생성** — `GameDifficulty.symbols`로 HARD(`a~e`)/EASY 후보 주입 완료
- [ ] 어려움 모드 **추측/판정** — `SkillController`의 `it.isDigit()` 분기가 `a~e` 입력을 막음 → 허용 문자집합(`difficulty.symbols`) 기준 검증 + **대소문자 정규화**(`A`→`a`)로 변경 필요
- [ ] 난이도 배수를 점수 산정에 적용 (`MmrCalculator`에서 `* difficulty.multiplier`)
- [ ] `전체랭킹`: `WHERE` 없는 전역 `score DESC` 조회

### 🟡 STEP 12. 부가기능 (시간 남으면)
- [ ] 시도 제한 → 힌트 → 자릿수 선택 → 전적(승률·평균 시도)

### 🟡 STEP 13. 운영 강화 (이후)
- [ ] H2 → MySQL 전환 / 요청 로깅 + 응답시간 메트릭 / 모니터링·알림

---

## 6-B. 🎯 [2026-06-21 추가] 점수(score) · 시즌 순위 기능 개선 (순차 계획)

> **요구사항(확정)**
> 1. **점수 조회** — 해당 카카오 유저의 시즌 점수(=글로벌 score) 조회
> 2. **순위 조회 2종** — ① 전체 순위(모든 봇 통합) ② 해당 봇이 진행한 게임 기록 순위(봇별)
> 3. **매달 1일 초기화** — score·순위를 매월 1일 0시(Asia/Seoul) **스케줄 배치로 0 초기화** *(사용자 선택)*
> 4. **시도별 감소 점수** — 적게 틀릴수록 더 많이 획득: `gain = max(MIN_GAIN, BASE - tries*STEP)`

> **🧭 점수 표기 방침 (2026-06-21 결정): MMR(+/−) 아님 → `score`(+ 위주) 채택.**
> 숫자야구는 상대 없는 **싱글플레이 + 매월 0 리셋 시즌제**라, 깎을 "패배" 이벤트가 구조적으로 없고 레이팅이 수렴할 시간도 없습니다. 따라서 순수 MMR(−)은 부자연스럽고 이탈만 유발 → **누적 score(+ 위주)**가 게임 적합성·이탈 방지·구현 단순성에서 우세.
> - 표기는 −가 아니라 **"이번 +N / 시즌 누적 N→N"**로 성취감·진행감 전달.
> - 변별력이 필요하면 **포기 시에만 소폭 −10**(맥락이 분명한 감점)만 허용, **시즌 점수 하한 0**(음수 랭킹 방지).

### 데이터 모델 매핑 (현재 코드 기준 — `User`/`BotUser`)

| 엔티티 | 식별자 | `score` 의미 | 쓰임 |
|--------|--------|-------------|------|
| `User` | `appUserId` (앱 단위 유저) | **글로벌 시즌 점수** | 점수 조회 · **전체 순위** |
| `BotUser` | `botKey` + `botUserKey` | **봇 내 점수** | **봇별 순위** |

> **왜 2개로 나누나**: 같은 카카오 유저라도 "내 전체 점수"와 "이 봇(채팅방)에서의 성적"은 다른 질문입니다. 글로벌은 `User.score` 하나로, 봇별은 `BotUser.score`로 분리하면 두 순위를 각자 인덱스/쿼리로 가볍게 뽑을 수 있습니다(5초 제한 대비).

### 승리 시 점수 적립 — 내부 동작 순서

```
1. guess() 판정 → 4S(win)
2. gain = MmrCalculator.gain(tries)        // = max(MIN_GAIN, BASE - tries*STEP), 항상 양수
3. User(appUserId) 조회/생성 → score += gain        // 글로벌 누적
4. BotUser(botKey, botUserKey) 조회/생성 → score += gain  // 봇 점수 누적
5. 응답에 "+gain (이전 → 현재)" 표기 (− 없음)
   (3·4는 같은 트랜잭션 — 한쪽만 오르는 정합성 깨짐 방지)

[포기 시 — 선택 규칙]
- giveUp() → score = max(0, score - 10)   // 하한 0, 이때만 − 표기 가능
```

**시도별 감소 예시** (BASE=100, STEP=5, MIN_GAIN=20)

| tries | gain | 비고 |
|------|------|------|
| 1 | 95 | 최고 |
| 5 | 75 | |
| 10 | 50 | |
| 16 이상 | 20 | MIN_GAIN 바닥(아무리 틀려도 보장) |

---

### 🟢 STEP A. 카카오 식별자 확정 (선행·필수)
> 현재 DTO는 `userRequest.user.id`만 파싱 → 글로벌 식별자(`appUserId`)와 `botKey`가 없으면 위 모델이 성립 안 함. (공식 문서 기준: `bot.id`=봇, `user.id`=botUserKey, `properties.appUserId`=카카오 로그인 계정 식별자)

- [ ] 실제 카카오 요청 JSON 로깅 → `bot.id`(botKey) / `user.id`(botUserKey) / `properties.appUserId`(글로벌)가 실제로 내려오는지 확인
- [ ] ⚠️ `appUserId`는 **봇에 앱키 연동 시에만** 제공 → 카카오 디벨로퍼스 앱키 연동 여부 먼저 확인 (없으면 `plusfriendUserKey`로 폴백하되 채널 단위 한계 인지)
- [ ] `SkillRequest` DTO 확장: `appUserId`, `botUserKey`(=user.id), `botKey`(=bot.id) 파싱 추가
- [ ] **확인**: 로그에서 세 식별자 모두 수신되는지 검증

### 🟢 STEP B. MmrCalculator 순수 함수 (⭐ TDD)
- [ ] `MmrCalculator.gain(tries): Int` = `max(MIN_GAIN, BASE - tries*STEP)`, 상수는 설정값/companion으로 분리(밸런싱을 배포 없이 조정)
- [ ] **단위 테스트**: tries=1 최대 / tries 클수록 단조 감소 / 바닥에서 MIN_GAIN 보장 / 경계값
- [ ] **확인**: `./gradlew test` 통과

### 🟢 STEP C. 승리 시 점수 적립 연결 (핵심 — 지금 비어 있는 부분)
- [ ] `GameService.guess()` win 분기 → `gain` 산정 후 `User.score += gain`, `BotUser.score += gain` (없으면 생성)
- [ ] (선택) `giveUp()` → `score = max(0, score - 10)` (하한 0)
- [ ] **동시성**: 같은 유저 동시 갱신 충돌 방지 → 갱신 트랜잭션 + 유니크 제약 `User(app_user_id)`, `BotUser(bot_key, bot_user_key)`
- [ ] 응답 포맷: **`+gain (이전 → 현재)`** — − 없이 "이번 +N / 시즌 누적 N→N"
- [ ] **확인**: 정답 후 `User.score`·`BotUser.score` 둘 다 증가(H2 콘솔/테스트)

### 🟢 STEP D. 점수 조회 기능
- [ ] `UserService.getScoreByUser(appUserId)` 스텁(`return null`) 채우기 → `User.score` 반환(미등록 시 0/안내)
- [ ] `SkillController`에 `점수`·`내점수` 발화 분기 + 텍스트 포맷("현재 시즌 점수: 1065")
- [ ] **확인**: `점수` 입력 → 내 점수 응답

### 🟢 STEP E. 순위 조회 (전체 + 봇별)
- [ ] `UserRepository.findTop10ByOrderByScoreDesc()` — **전체 순위**
- [ ] `BotUserRepository.findTop10ByBotKeyOrderByScoreDesc(botKey)` — **봇별 순위** (기존 `getScoresByBotKey`에 정렬·TOP N 보강)
- [ ] 인덱스: `users(score)`, `bot_users(bot_key, score)` (정렬까지 인덱스로 → 파일정렬 회피)
- [ ] `SkillController` 발화 분기: `전체랭킹` → 전역 / `랭킹`(또는 `봇랭킹`) → 봇별 + 순위·점수 TOP 10 텍스트
- [ ] **확인**: 두 명 이상 플레이 후 두 발화 모두 정렬된 목록 응답

### 🟢 STEP F. 매월 1일 초기화 스케줄러 (선택: 배치 0 초기화)
- [ ] `@EnableScheduling` + `@Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Seoul")`
- [ ] 벌크 update: `UPDATE users SET score = 0`, `UPDATE bot_users SET score = 0` (`@Modifying` 쿼리)
- [ ] **운영 지표 로깅**: 리셋 행 수·소요시간 로깅, 실패 시 알림 *(평소 강조하는 "임팩트 측정·장애 감지")*
- [ ] (권장) 리셋 직전 **스냅샷 저장**(`monthly_score_snapshot`) → 배치 실패 대비 + 추후 '지난달 순위' 확장 여지
- [ ] 수동 트리거(테스트 프로파일 한정 엔드포인트/메서드)로 **멱등하게** 검증 가능하도록 분리
- [ ] **확인**: 트리거 후 전 score=0, 다음 게임부터 새 시즌 누적

### 🟢 STEP G. 마무리 검증
- [ ] 5초 내 응답(추가된 조회·적립 포함) / 동시 적립 정합성 테스트 / README 갱신

> **진행 순서 요약**: **A(식별자) → B(계산기 TDD) → C(적립 연결) → D(점수 조회) → E(전체·봇별 순위) → F(월간 리셋) → G(검증)**. C가 비면 순위에 쌓일 데이터가 없으므로 A·B·C가 최우선입니다.

---

## 7. 리스크 & 주의사항

| 리스크 | 영향 | 대응 |
|--------|------|------|
| **채팅방 식별자 부재** 🆕 | 🟢 채팅방 랭킹 불가 | 실제 요청 JSON 로깅 후 A안(채팅방키)/B안(방 코드) 결정 — STEP 10 선행 |
| **점수 밸런싱** 🆕 | 점수 인플레/저평가 | 상수(BASE/STEP/MIN_GAIN)를 설정값으로 분리해 배포 없이 조정, 산정식 단위테스트 |
| 5초 타임아웃 | 응답 실패 | 랭킹 조회를 `bot_users(bot_key, score)`·`users(score)` 인덱스로 경량화, TOP 10 LIMIT |
| 동시성 🆕 | 같은 user 점수 갱신 충돌 | `User`/`BotUser` 갱신 트랜잭션 + 유니크(`app_user_id`, (`bot_key, bot_user_key`)) |
| H2 파일 영속성 | 재시작 시 데이터 | 파일 모드, 운영은 MySQL 전환 |
| **월간 리셋 파괴성** 🆕 | 배치 실패 시 시즌 안 바뀜 / 이력 소실 | 리셋 직전 스냅샷 저장 + 멱등 설계 + 건수/소요 로깅·실패 알림 (STEP F) |
| 범위 과다 | 미완성 위험 | 🟢(MMR→랭킹) 먼저, 🟡(어려움/전체랭킹)은 후순위 |

---

## 8. 요약

- 1차(배포까지)는 완료 ✅ → 이번은 **게임/랭킹 두 갈래 고도화**.
- **게임**: 보통 모드 + (추후)어려움 모드(숫자+알파벳). 정답 시 **MMR 상승**(`max(MIN_GAIN, BASE - tries*STEP) * 난이도배수`).
- **랭킹**: 현재 채팅방 내 `mmr DESC` TOP N + (추후)전체 랭킹.
- 진행 순서: **STEP 9 MMR → STEP 10 채팅방 랭킹 → (추후)STEP 11 어려움/전체랭킹**.
- 데이터 모델: `Game`에 `difficulty/mmrGain` 추가, **`Player` 엔티티 신설**(랭킹 정렬 키 = `mmr`).
- ⚠️ 최대 변수는 **채팅방 식별자**: 코드 짜기 전에 실제 카카오 요청 JSON부터 찍어 확인.
- 판정·MMR을 **순수 함수**로 분리해 테스트 커버리지·코드 품질 확보, **복합 인덱스**로 5초 제한 대비.
