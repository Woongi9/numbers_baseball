# CLAUDE.md

이 파일은 이 저장소에서 작업할 때 Claude Code(claude.ai/code)에 지침을 제공한다.

## Project

Kotlin + Spring Boot 3 챗봇으로, 숫자야구(Bulls and Cows / "Numbers Baseball")를 카카오 오픈빌더(OpenBuilder) 스킬 서버로 구현한다. 카카오가 사용자 발화와 함께 호출하는 단일 웹훅(`POST /skill/play`)을 노출하며, 카카오의 **5초 타임아웃** 안에 응답해야 한다. 전체 기능/설계 이력과 근거는 `PLAN.md`, AWS 배포 상세는 `infra.md` / `DEPLOY_RUNBOOK.md`를 참고.

## Commands

- 빌드: `./gradlew build`
- 테스트 실행: `./gradlew test`
- 단일 테스트 클래스 실행: `./gradlew test --tests "com.example.baseball.service.BaseballJudgeTest"`
- 단일 테스트 메서드 실행: `./gradlew test --tests "com.example.baseball.service.BaseballJudgeTest.구체적인 테스트 이름"`
- 로컬 실행: `./gradlew bootRun` (기본 `local` 프로파일, 로컬 MySQL 필요 — 아래 참고)
- 배포용 jar 빌드: `./gradlew bootJar -Pprofile=prod` (jar 이름은 `build/libs/baseball.jar`; **오직** `-Pprofile=dev`만 `baseball-dev.jar`로 이름을 바꿔, 공유 EC2 호스트에서 dev 배포가 prod 산출물을 덮어쓰지 못하게 한다 — `build.gradle.kts`의 `bootJar` 참고)
- `local` 프로파일용 로컬 MySQL: `docker compose up -d` (`bootRun` 전에 떠 있어야 함; 테스트는 **불필요** — in-memory H2 사용)
- 로컬 실행 시 Swagger UI: `http://localhost:8080/swagger-ui.html`

### Profiles

Gradle `-Pprofile=<name>` 속성(기본 `local`)으로 제어하며, 빌드 시점에 `src/main/resources-env/<name>/application.yml`을 선택해 `src/main/resources/application.yml`과 함께 병합한다(`build.gradle.kts`의 `sourceSets` 참고).

- `local` — docker-compose MySQL, `ddl-auto: update`, Swagger 활성, BasicCard 이미지가 `localhost:8080/images`를 가리킴. 추가 yml이 `resources-env/` 아래 사는 유일한 프로파일(`resources-env/local/application.yml`); `dev`/`prod`는 대신 런타임 활성 `application-<name>.yml`(아래)을 쓰므로 `-Pprofile=dev|prod`는 jar 이름에만 영향.
- `dev` — **prod와 같은 EC2 호스트**에서 도는 스테이징 인스턴스지만, `dev.numbers-baseball.com` 뒤 포트 `9090`에서 같은 RDS의 별도 `baseball_dev` 스키마 사용. 설정은 `src/main/resources/application-dev.yml`, `SPRING_PROFILES_ACTIVE=dev`(systemd `baseball-dev.service`)로 활성, 시크릿은 `/home/ubuntu/baseball-dev.env`에서 주입. `ddl-auto: update`와 Swagger 유지; nginx/Cloudflare TLS 종단 뒤에서 Swagger가 `https` URL을 만들도록 `forward-headers-strategy: framework` 필요.
- `prod` — 설정은 `src/main/resources/application-prod.yml`, 서버에서 `SPRING_PROFILES_ACTIVE=prod`로 활성(포트 `8080`, `numbers-baseball.com`). DB 자격 증명은 저장소에 **절대** 없음 — CI 시크릿이나 커밋된 yml이 아니라 `/home/ubuntu/baseball.env`(systemd `EnvironmentFile`)에서 환경 변수로 주입.
- 테스트는 `src/test/resources/application.yml` 사용 — in-memory H2, `create-drop`.

## Architecture

### Request flow

`SkillController.play()`가 유일한 HTTP 진입점. `SkillRequest`에서 `userId`(카카오 `user.id`)와 `botKey`(`chat.properties.botGroupKey`, nullable — 그룹/오픈채팅 봇에서만 존재)를 추출하고, `SkillCommand.classify()`로 발화를 분류한 뒤 `GameService` / `RankingService`로 디스패치한다. 명령 분류를 `SkillCommand`에 집중시킨 건 컨트롤러의 분기와 로깅 aspect의 `intent` 필드가 어긋나지 않게 하기 위함이다.

예외는 컨트롤러에서 잡지 않는다 — `IllegalStateException`/`IllegalArgumentException`(예: "진행 중 게임 없음", 잘못된 추측 포맷)은 `SkillExceptionHandler`(`@RestControllerAdvice`)로 전파되고, 여기서 안내 메시지가 담긴 정상 200 응답으로 변환한다(카카오 스킬은 non-200/에러 바디를 반환하면 안 됨). 이는 의도적이다: `LogTraceAspect`가 `SkillController.play`를 `@Around`로 감싸며, 예외가 다운스트림에서 삼켜지기 전에 이 aspect를 통과해야(실패를 로깅하려면) 한다.

### Key domain quirk: `Game.botKey` actually holds `userId`

`GameService`는 *게임 세션* 조회(`gameRepository.findFirstByBotKeyAndStatus(...)`)를 `botKey` 파라미터/컬럼에 `userId`를 넘겨서 키잉한다. 의도적이지만 헷갈린다: `Game`은 채팅방 단위가 아니라 사용자 단위(한 사용자당 동시에 활성 게임 하나)로 스코프된다. 실제 채팅방/그룹 식별자(요청의 `botKey`)는 점수 귀속(`BotUser`)과 랭킹에만 따로 쓰이고, 게임 세션 조회에는 안 쓰인다. `GameService.startGame`/`guess`/`currentGame`을 다시 읽지 않고 "고치지" 말 것.

### Two-tier user/score model

- `User`(`appUserId`, 전역 `score`) — 카카오 앱 전역 계정, 전역 백분위 랭킹(`UserService.percentileOf`)에 사용.
- `BotUser`(`botKey` + `botUserKey`, 채팅방별 `score`) — 같은 사람의 한 봇/채팅방 내 순위, `랭킹`(`RankingService.getBotRanking`, `(bot_key, score)` 인덱스로 TOP 10)에 사용.

둘 다 `UserService.register`에서 함께 `getOrCreate`되고(게임 시작 시 호출되므로 첫 승리 전에도 참가자가 추적됨), 둘 다 `UserService.accrue`에서 함께 누적된다(승리 시에만 호출, `GameService.guess`의 게임 상태 전이와 같은 트랜잭션). `botKey`가 null이면(1:1 채팅, 그룹 식별자 없음) 전역 `User`만 건드린다.

### Kakao identifiers: `botKey` vs `botUserKey` vs `appUserId`

먼저 개념부터. 카카오가 보내는 사용자 식별자는 두 종류다:

- **`appUserId`** — 카카오 **앱 기준**(전역) 사용자 키. 같은 사람이면 어느 봇/채팅방이든 같은 값. 우리 `User` 엔티티가 이걸 매핑한다.
- **`botUserKey`** — 카카오 **챗봇 기준** 사용자 키. 같은 사람이어도 챗봇이 다르면 다른 값일 수 있다. 우리 `BotUser` 엔티티가 이걸 매핑한다.

여기에 채팅방 식별자가 하나 더 있다:

- **`botKey`** — 채팅방/그룹 식별자(`chat.properties.botGroupKey`, nullable; 그룹/오픈채팅에서만). *어느 방*인지를 가리킬 뿐 누구인지가 아니다. 채팅방별 점수 귀속·랭킹(`BotUser.botKey`)에 쓰고, 게임 세션 조회엔 안 쓴다(그 quirk는 `userId`로 키잉 — 위 참고).

엔티티 매핑:

- `User` — PK는 `id`, 카카오 전역 유저를 `appUserId` 컬럼으로 매핑. 전역 점수/백분위 랭킹용.
- `BotUser` — PK는 `id`, 카카오 챗봇-유저를 `botUserKey` 컬럼으로 매핑(+ `botKey`로 어느 방인지). 채팅방별 점수/랭킹용. 멘션 id로도 재사용(`Mention(type="botUserKey", ...)`).

**중요 — 현재 구현은 위 두 카카오 필드를 실제로 읽지 않는다.** `SkillController.play`는 요청에서 `user.id` **하나**와 `botGroupKey`만 꺼내고, `GameService.startGame`이 `register(appUserId = userId, botUserKey = userId)`로 **`appUserId`·`botUserKey` 컬럼 양쪽에 같은 `user.id`**를 넣는다. 즉 개념상 두 컬럼이지만 값 출처는 하나(`user.id`)이며, 카카오의 진짜 `appUserId`/`botUserKey` 페이로드 필드는 미사용이다. 진짜 크로스-봇 앱 정체는 아직 안 쓴다.

**유일성 / 집계 규칙(grain):** `BotUser`의 정체성은 `(botKey, botUserKey)` — 그게 유니크 제약이다. 카카오는 `botUserKey`가 방 간 유일하다고 **보장하지 않으므로**(챗봇 기준 키), 두 방의 같은 사람이 `botUserKey`는 같고 `botKey`만 다른 두 `BotUser` 행이 될 수 있다. **`BotUser`를 스냅샷/집계하는 모든 테이블은 `(botKey, botUserKey)` grain을 써야 한다** — 예: `season_bot_scores`는 `UNIQUE(year_month, bot_key, bot_user_key)`; `bot_key`를 빼면 유니크 충돌로 전체 쓰기가 롤백될 위험(이게 시즌 리셋 스냅샷 설계를 물었다 — `docs/2026-07-22-season-score-history-design.md` 참고).

### Pure-function core (unit tested, framework-free)

판정과 점수 계산 로직은 Spring/JPA/카카오와 무관하게 의도적으로 분리해 두어 빠르고 단위 테스트가 쉽다 — 게임 규칙이나 점수 변경은 이 계층을 확장한다:

- `BaseballJudge.judge(answer, guess)` — 스트라이크/볼/아웃 판정 + 입력 검증.
- `ScoreCalculator.gain(tries, difficulty)` — `max(MIN_GAIN, BASE - tries*STEP) * difficulty.multiplier`.
- `PercentileCalculator.of(higher, total)` — 두 카운트를 순위/백분위로 변환, `MIN_SAMPLE` 미만이면 `null` 반환(실제 코호트가 없을 때 무의미한 "상위 100%"를 피함).
- `RankTitle.of(topPercent)` — 상위 백분위용 장식 티어 배지.
- `GameDifficulty` enum — `EASY`/`NORMAL`/`HARD`, 각각 `symbols` 후보 집합과 점수 `multiplier`를 가짐. 난이도는 정답의 *문자 집합*을 바꾸지(숫자만 vs 숫자+문자) 자릿수를 바꾸지 않는다 — 자릿수는 `GameService.DIGITS = 4`로 고정.

`GameService`와 `UserService`가 영속성(`GameRepository`, `UserRepository`, `BotUserRepository`)을 건드리는 유일한 곳이며, 순수 함수들을 `@Transactional` 경계로 감싼다.

### Response shape: BasicCard with simpleText fallback

`SkillController`는 `kakao.image-base-url`이 설정되면(`prod`/`local`) 카카오 BasicCard/TextCard 응답(썸네일 + 제목/설명 + 버튼)을 만들고, 비어 있으면(테스트 프로파일 / 이미지 미설정) 순수 `simpleText`로 폴백한다 — BasicCard의 `thumbnail` 필드가 카카오 스펙상 **필수**이므로, 보여줄 이미지가 없을 때 응답을 유효하게 유지하려는 폴백이다. `SkillResponse.BasicCard`/`TextCard`는 생성 시점에 제목/설명 길이 제한을 검증하고(50/230/400자 캡에서 fail fast), `SkillController.cardOrText`/`textCardOrText`는 추가로 생성 전에 `.take()`하여 런타임 데이터가 그 검증을 절대 못 건드리게 한다. 카드 타입은 명령별로 다르다: START/GUESS → BasicCard(썸네일), GIVEUP/RULES → TextCard(썸네일 없음), RANKING/HELP → 순수 `simpleText`.

### Kakao open-chat quirks (mentions, button prefill)

다시 논쟁하기 쉬운 두 함정, 둘 다 실제 배포 피드백으로 굳혔다(`PLAN.md` STEP 12와 2026-07-09 무렵 `git log` 참고):

- **랭킹 멘션은 `Mention(type = "botUserKey", ...)`를 써야 한다**, `"appUserId"`가 아니라. `SkillController.formatRanking`은 `RankingService`의 채팅방별 항목에서 소싱한 `extra.mentions`로 `{{#mentions.userN}}` 플레이스홀더를 채운다. `appUserId` 타입 멘션은 봇이 채널에 카카오 앱 키를 연결한 경우에만 resolve되는데, 보장되지 않는다 — `botUserKey`(== 요청자의 `user.id`)는 오픈채팅에서 항상 resolve된다. `appUserId`로 바꿨다가 같은 날 되돌렸다(커밋 `637a480` → `38382b8`); 앱 키 연결을 확인하지 않고 되돌리지 말 것.
- **`SkillResponse.Button.mentionPrefill`은 `messageText`로 zero-width space(`​`)를 보낸다, `""`나 `" "`가 아니라.** 오픈채팅에서 `message` 액션 버튼은 입력창을 `"@봇 " + messageText`로 프리필한다; `messageText`가 비었거나 공백이면 카카오가 "값 없음"으로 취급하고 버튼의 `label`로 대체한다(예: `"@봇 제출"`로 label 노출). zero-width space는 보이지 않지만 non-blank라 `"@봇 "`만 보인다. 그 문자가 다음 발화에 앞에 붙어 도착하므로, `SkillController.play`는 분류 전에 zero-width 문자를 제거한다(`zeroWidthChars` 정규식) — 이 제거를 없애면 `"​1234"` 같은 프리필된 추측이 `SkillCommand`의 `all isDigit()` 체크에 조용히 안 걸린다.

### Observability

`LogTraceAspect`(`SkillController.play`에 `@Around`)는 요청당 `phase=START`/`phase=END` 라인을 로깅한다(MDC를 통한 traceId, `botKey`/`user` id, 분류된 intent, 발화, 경과 시간, `SLOW_THRESHOLD_MS=3000`에서 `slow` 플래그), 그리고 예외를 로깅한 뒤 다시 던져 `SkillExceptionHandler`가 여전히 사용자용 응답으로 변환하게 한다. 참고: `PLAN.md`는 마스킹 스킴을 기술하지만 user/bot 식별자는 마스킹 없이 그대로 로깅된다 — 그 PLAN 섹션에 의존하기 전에 현재 코드를 확인할 것.

## Deployment

두 개의 병렬 GitHub Actions 워크플로가 **같은 EC2 호스트**로 배포하며, 브랜치로 나뉘고 별도 `concurrency` 그룹에 두어 서로 취소하지 않는다:

- `.github/workflows/deploy.yml` — `main` 푸시 → prod. `./gradlew clean test bootJar -Pprofile=prod` 실행(테스트가 품질 게이트 — 빨간 빌드는 서버에 절대 도달 안 함), 이어서 jar와 `deploy/scripts/*.sh`를 EC2로 `scp`하고 SSH로 `stop.sh` → `start.sh` 실행. `start.sh`는 새 jar로 교체하고 systemd `baseball` 서비스를 재시작하며, `/actuator/health`가 ~30초 내 `UP`을 보고하지 않으면 이전 jar(`baseball.jar.bak`)로 롤백한다.
- `.github/workflows/deploy-dev.yml` — `develop` 푸시 → dev/스테이징. 같은 형태지만 `-Pprofile=dev`(`baseball-dev.jar` 생성)이고 포트 `9090`의 systemd `baseball-dev` 서비스에 대해 `stop-dev.sh`/`start-dev.sh` 사용.

호스트의 nginx는 `Host` 헤더로 라우팅하는 리버스 프록시(`numbers-baseball.com` → `8080`, `dev.numbers-baseball.com` → `9090`); TLS는 Let's Encrypt가 아니라 origin cert로 Cloudflare 엣지에서 종단된다(Full 모드). `deploy/nginx-baseball.conf`는 전체 prod+dev 목표 상태의 레퍼런스 — 런북은 prod 설정이 안 건드려지도록 두 dev 블록을 별도 `sites-available` 파일로 분리하길 권한다. 전체 인프라 근거(AWS 사이징, 보안 그룹, CloudWatch 알람)는 `infra.md`, 단계별 콘솔 설정은 `DEPLOY_RUNBOOK.md`.
