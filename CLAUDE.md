# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kotlin + Spring Boot 3 chatbot that implements 숫자야구 (Bulls and Cows / "Numbers Baseball") as a Kakao 오픈빌더 (OpenBuilder) skill server. It exposes a single webhook (`POST /skill/play`) that Kakao calls with the user's utterance and must respond within Kakao's **5-second timeout**. See `PLAN.md` for the full feature/design history and rationale, and `infra.md` / `DEPLOY_RUNBOOK.md` for AWS deployment details.

## Commands

- Build: `./gradlew build`
- Run tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.example.baseball.service.BaseballJudgeTest"`
- Run a single test method: `./gradlew test --tests "com.example.baseball.service.BaseballJudgeTest.구체적인 테스트 이름"`
- Run locally: `./gradlew bootRun` (defaults to the `local` profile, needs local MySQL — see below)
- Build the deployable jar: `./gradlew bootJar -Pprofile=prod` (jar is named `build/libs/baseball.jar`; **only** `-Pprofile=dev` renames it to `baseball-dev.jar` so the dev deploy can't overwrite the prod artifact on the shared EC2 host — see `bootJar` in `build.gradle.kts`)
- Local MySQL for the `local` profile: `docker compose up -d` (must be running before `bootRun`; tests do **not** need this — they use in-memory H2)
- Swagger UI when running locally: `http://localhost:8080/swagger-ui.html`

### Profiles

Controlled by the Gradle `-Pprofile=<name>` property (default `local`), which selects `src/main/resources-env/<name>/application.yml` to be merged in alongside `src/main/resources/application.yml` at build time (see `sourceSets` in `build.gradle.kts`).

- `local` — MySQL via docker-compose, `ddl-auto: update`, Swagger enabled, BasicCard images point at `localhost:8080/images`. This is the only profile whose extra yml lives under `resources-env/` (`resources-env/local/application.yml`); `dev`/`prod` instead use a runtime-activated `application-<name>.yml` (below), so `-Pprofile=dev|prod` only affects the jar name.
- `dev` — staging instance that runs on the **same EC2 host as prod**, but on port `9090` behind `dev.numbers-baseball.com`, using a separate `baseball_dev` schema on the same RDS. Config lives in `src/main/resources/application-dev.yml`, activated via `SPRING_PROFILES_ACTIVE=dev` (systemd `baseball-dev.service`), secrets injected from `/home/ubuntu/baseball-dev.env`. `ddl-auto: update` and Swagger stay on; needs `forward-headers-strategy: framework` so Swagger builds `https` URLs behind the nginx/Cloudflare TLS termination.
- `prod` — config lives in `src/main/resources/application-prod.yml`, activated via `SPRING_PROFILES_ACTIVE=prod` on the server (port `8080`, `numbers-baseball.com`). DB credentials are **never** in the repo — they're injected as env vars from `/home/ubuntu/baseball.env` (systemd `EnvironmentFile`), not via CI secrets or committed yml.
- Tests use `src/test/resources/application.yml` — in-memory H2, `create-drop`.

## Architecture

### Request flow

`SkillController.play()` is the only HTTP entrypoint. It extracts `userId` (Kakao `user.id`) and `botKey` (`chat.properties.botGroupKey`, nullable — only present for group/open-chat bots) from `SkillRequest`, classifies the utterance via `SkillCommand.classify()`, and dispatches to `GameService` / `RankingService`. Command classification is centralized in `SkillCommand` specifically so the controller's branching and the logging aspect's `intent` field can't drift apart.

Exceptions are not caught in the controller — `IllegalStateException`/`IllegalArgumentException` (e.g. "no game in progress", bad guess format) propagate to `SkillExceptionHandler` (`@RestControllerAdvice`), which converts them into a normal 200 response with a guidance message (Kakao skills should not return non-200/error bodies). This is deliberate: `LogTraceAspect` wraps `SkillController.play` with `@Around` and needs the exception to pass through it (to log the failure) before being swallowed downstream.

### Key domain quirk: `Game.botKey` actually holds `userId`

`GameService` keys the *game session* lookup (`gameRepository.findFirstByBotKeyAndStatus(...)`) by passing `userId` into the `botKey` parameter/column. This is intentional but confusing: a `Game` is scoped per-user (one active game per user at a time), not per chatroom. The real chatroom/group identifier (`botKey` from the request) is only used separately for score attribution (`BotUser`) and ranking — not for game session lookup. Don't "fix" this without re-reading `GameService.startGame`/`guess`/`currentGame`.

### Two-tier user/score model

- `User` (`appUserId`, global `score`) — the Kakao app-wide account, used for global percentile ranking (`UserService.percentileOf`).
- `BotUser` (`botKey` + `botUserKey`, per-chatroom `score`) — the same person's standing within one bot/chatroom, used for `랭킹` (`RankingService.getBotRanking`, TOP 10 by `(bot_key, score)` index).

Both are `getOrCreate`d together in `UserService.register` (called at game start, so participants are tracked even before their first win) and both accrue together in `UserService.accrue` (called only on a win, same transaction as the game state transition in `GameService.guess`). If `botKey` is null (1:1 chat, no group identifier), only the global `User` is touched.

### Pure-function core (unit tested, framework-free)

Judging and scoring logic is deliberately kept free of Spring/JPA/Kakao so it's fast and trivial to unit test — this is the layer to extend for game-rule or scoring changes:

- `BaseballJudge.judge(answer, guess)` — strike/ball/out judgement + input validation.
- `ScoreCalculator.gain(tries, difficulty)` — `max(MIN_GAIN, BASE - tries*STEP) * difficulty.multiplier`.
- `PercentileCalculator.of(higher, total)` — turns two counts into a rank/percentile, returns `null` below `MIN_SAMPLE` (avoids meaningless "top 100%" when there's no real cohort yet).
- `RankTitle.of(topPercent)` — cosmetic tier badge for top percentiles.
- `GameDifficulty` enum — `EASY`/`NORMAL`/`HARD`, each with a `symbols` candidate set and a score `multiplier`. Difficulty changes the *character set* for the answer (digits only vs. digits+letters), not the number of digits — digit count is fixed at `GameService.DIGITS = 4`.

`GameService` and `UserService` are the only places that touch persistence (`GameRepository`, `UserRepository`, `BotUserRepository`) and wrap the pure functions in `@Transactional` boundaries.

### Response shape: BasicCard with simpleText fallback

`SkillController` builds Kakao BasicCard/TextCard responses (thumbnail + title/description + buttons) when `kakao.image-base-url` is configured (`prod`/`local`), and falls back to plain `simpleText` when it's blank (test profile / no images configured) — because a BasicCard's `thumbnail` field is **required** by Kakao's spec, so the fallback exists to keep responses valid when there's no image to show. `SkillResponse.BasicCard`/`TextCard` validate title/description length limits at construction time (fail fast on the 50/230/400-char caps), and `SkillController.cardOrText`/`textCardOrText` additionally `.take()` before constructing so runtime data can never trip that validation. Card type varies by command: START/GUESS → BasicCard (thumbnail), GIVEUP/RULES → TextCard (no thumbnail), RANKING/HELP → plain `simpleText`.

### Kakao open-chat quirks (mentions, button prefill)

Two easy-to-relitigate gotchas, both hardened by real deploy feedback (see `PLAN.md` STEP 12 and the `git log` around 2026-07-09):

- **Ranking mentions must use `Mention(type = "botUserKey", ...)`**, not `"appUserId"`. `SkillController.formatRanking` fills `{{#mentions.userN}}` placeholders via `extra.mentions`, sourced from `RankingService`'s per-chatroom entries. `appUserId`-typed mentions only resolve when the bot has a Kakao app key linked to the channel, which isn't guaranteed — `botUserKey` (== the requester's `user.id`) always resolves in open chat. This was changed to `appUserId` and reverted same-day (commits `637a480` → `38382b8`); don't change it back without confirming app-key linkage first.
- **`SkillResponse.Button.mentionPrefill` sends a zero-width space (`​`) as `messageText`, never `""` or `" "`.** In open chat, a `message`-action button prefills the input box with `"@봇 " + messageText`; if `messageText` is empty/blank, Kakao treats it as "no value" and substitutes the button's `label` instead (e.g. `"@봇 제출"` leaking the label). The zero-width space is invisible but non-blank, so only `"@봇 "` shows. Because that character then arrives prepended to the user's next utterance, `SkillController.play` strips zero-width chars (`zeroWidthChars` regex) before classification — remove that stripping and prefilled guesses like `"​1234"` silently stop matching `SkillCommand`'s `all isDigit()` check.

### Observability

`LogTraceAspect` (`@Around` on `SkillController.play`) logs a `phase=START`/`phase=END` line per request (traceId via MDC, `botKey`/`user` ids, classified intent, utterance, elapsed time, `slow` flag at `SLOW_THRESHOLD_MS=3000`), and rethrows exceptions after logging them so `SkillExceptionHandler` still converts them to a user-facing response. Note: user/bot identifiers are logged as-is (not masked) despite `PLAN.md` describing a masking scheme — check current code before relying on that PLAN section.

## Deployment

Two parallel GitHub Actions workflows deploy to the **same EC2 host**, split by branch and kept in separate `concurrency` groups so they never cancel each other:

- `.github/workflows/deploy.yml` — push to `main` → prod. Runs `./gradlew clean test bootJar -Pprofile=prod` (tests act as a quality gate — a red build never reaches the server), then `scp`s the jar and `deploy/scripts/*.sh` to EC2 and runs `stop.sh` → `start.sh` over SSH. `start.sh` swaps in the new jar, restarts the systemd `baseball` service, and rolls back to the previous jar (`baseball.jar.bak`) if `/actuator/health` doesn't report `UP` within ~30s.
- `.github/workflows/deploy-dev.yml` — push to `develop` → dev/staging. Same shape but `-Pprofile=dev` (produces `baseball-dev.jar`) and `stop-dev.sh`/`start-dev.sh` against the systemd `baseball-dev` service on port `9090`.

nginx on the host is a reverse proxy that routes by `Host` header (`numbers-baseball.com` → `8080`, `dev.numbers-baseball.com` → `9090`); TLS is terminated at Cloudflare's edge (Full mode) with an origin cert, not Let's Encrypt. `deploy/nginx-baseball.conf` is a reference of the full prod+dev target state — the runbook recommends splitting the two dev blocks into a separate `sites-available` file so prod config stays untouched. Full infra rationale (AWS sizing, security groups, CloudWatch alarms) is in `infra.md`; step-by-step console setup is in `DEPLOY_RUNBOOK.md`.
