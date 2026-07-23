# 카카오 식별자 정규화 + 방 단위 게임 세션 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카카오가 보내는 세 식별자(`appUserId`/`botUserKey`/`botKey`)를 각자의 실제 페이로드 경로에서 읽어 서비스 계층에 non-null 로 확정해 넘기고, 게임 세션을 유저 단위에서 채팅방 단위로 전환한다.

**Architecture:** `SkillRequest` DTO 는 모든 식별자 필드를 nullable 로 받아 역직렬화가 절대 실패하지 않게 하고, `ChatIdentity.from(request)` 가 유일한 non-null 확정 지점이 된다. 서비스 계층(`GameService`/`UserService`)은 `ChatIdentity` 하나만 받아 String 3개가 뒤바뀌는 사고를 타입으로 막는다. 게임 세션은 `game.bot_key` 에 진짜 방 키를 넣어 조회하고, "방당 PLAYING 1건" 불변식은 DB 제약이 아니라 `startGame` 의 전건 정리로 수렴시킨다.

**Tech Stack:** Kotlin, Spring Boot 3, Spring Data JPA, JUnit 5 + kotlin.test, MockK, MockMvc, H2(테스트) / MySQL(운영)

**설계 문서:** `docs/2026-07-23-kakao-identity-and-room-scoped-game-design.md`

## Global Constraints

- **커밋은 리뷰 후에.** 각 태스크의 마지막 단계는 커밋이 아니라 **변경 보고 + 리뷰 요청**이다. 사용자가 명시적으로 위임했을 때만 커밋한다 (`CLAUDE.md` → Working agreements).
- **주석은 아껴서.** 코드만 봐서는 이해가 어려운 곳에만 단다 — 겉보기와 다르게 동작하는 이유, 재논쟁하기 쉬운 결정의 근거, 외부(카카오·Hibernate) 함정. 코드를 그대로 풀어 쓴 설명·자명한 `@param`·형식적 KDoc 은 달지 않고, 손대는 파일에서 그런 주석이 보이면 지운다 (`CLAUDE.md` → Working agreements).
- **테스트 실행에는 JDK 17 또는 21 이 필요하다.** 이 머신의 기본 JDK 는 26 이고 Gradle 8.10 이 지원하지 않아 `./gradlew test` 가 `* What went wrong: 26.0.1` 로 즉시 실패한다. 작업 셸에서 먼저 실행한다:
  ```
  export JAVA_HOME=/Users/eomjin-ung/Library/Java/JavaVirtualMachines/corretto-17.0.15/Contents/Home
  ```
  이 계획의 모든 `./gradlew` 명령은 이 `JAVA_HOME` 이 export 된 셸에서 실행한다고 가정한다.
- **카카오 스킬은 non-200 을 반환하면 안 된다.** 모든 예외는 `SkillExceptionHandler` 를 거쳐 200 + 안내 메시지가 된다.
- **점수·랭킹 노출은 언제나 `appUserId` 로 조회한 `User.score` 기준이다.** `BotUser.score` 는 적립만 되고 화면에 나오지 않는다.
- **기존 데이터 이관은 하지 않는다.** prod 미배포 상태이고 `ddl-auto: update` 라 스키마는 자동 반영된다 (설계 D1).
- **시작 상태**: 브랜치 `fix/botKey`, 커밋 `e6725db` 기준 **117개 테스트 중 13개 실패**. Task 3 종료 시 전부 통과해야 한다.

## File Structure

**신규**

| 파일 | 책임 |
|---|---|
| `src/main/kotlin/com/example/baseball/dto/ChatIdentity.kt` | 세 식별자의 non-null 확정 + 폴백 규칙 단일 지점. `MissingAppUserIdException` 도 여기 둔다(같이 변경되므로 같이 산다). |
| `src/test/kotlin/com/example/baseball/dto/ChatIdentityTest.kt` | 폴백 우선순위·예외 규칙 순수 단위 테스트(Spring 없음). |

**수정**

| 파일 | 변경 | 태스크 |
|---|---|---|
| `dto/SkillRequest.kt` | 식별자 nullable 필드 추가 | 1 |
| `controller/SkillController.kt` | `ChatIdentity` 배선 → 랭킹 null 분기 제거 → 교체 안내 문구 | 1, 2, 3 |
| `controller/SkillExceptionHandler.kt` | `MissingAppUserIdException` 핸들러 추가 | 1 |
| `logging/LogTraceAspect.kt` | 식별자 추출을 `ChatIdentity.fromOrNull` 로 통일 | 1 |
| `domain/game/GameRepository.kt` | 방 단위 쿼리 2종으로 교체 | 2 |
| `domain/game/Game.kt` | 낡은 클래스 주석 수정 | 2 |
| `service/GameService.kt` | `ChatIdentity` 파라미터, 방 단위 조회/정리, `StartOutcome` 반환 | 2 |
| `service/UserService.kt` | `ChatIdentity` 파라미터, `botKey` null 분기 2개 제거 | 2 |
| `test/.../GameServiceTest.kt`, `UserServiceTest.kt` | 새 시그니처로 갱신 + 유령 게임 시나리오 | 2 |
| `test/.../SkillControllerIntegrationTest.kt`, `SkillControllerCardTest.kt` | 새 페이로드·방 키 + 방 단위 시나리오 | 1, 2, 3 |

**태스크 경계의 근거:** `GameService` 와 `UserService` 는 서로를 호출하므로 시그니처를 따로 바꾸면 중간 상태가 컴파일되지 않는다. 그래서 Task 2 에서 함께 바꾼다.

---

### Task 1: 식별자 추출 계층 도입 및 배선

`ChatIdentity` 를 만들고 컨트롤러·aspect·예외 핸들러까지 한 번에 배선한다. DTO 의 `chat` 을 nullable 로 되돌리는 순간 `SkillController` 가 컴파일되지 않고, 테스트 페이로드에 `properties` 가 없으면 전부 `MissingAppUserIdException` 이 되므로 이 셋은 쪼갤 수 없다.

이 태스크에서 `GameService`/`UserService` 시그니처는 건드리지 않는다. 컨트롤러가 `ChatIdentity` 의 값을 풀어서 기존 시그니처로 넘긴다.

**Files:**
- Create: `src/main/kotlin/com/example/baseball/dto/ChatIdentity.kt`
- Create: `src/test/kotlin/com/example/baseball/dto/ChatIdentityTest.kt`
- Modify: `src/main/kotlin/com/example/baseball/dto/SkillRequest.kt` (전체 교체)
- Modify: `src/main/kotlin/com/example/baseball/controller/SkillController.kt` (`play`, `handle`, `formatRanking`)
- Modify: `src/main/kotlin/com/example/baseball/controller/SkillExceptionHandler.kt` (핸들러 1개 추가)
- Modify: `src/main/kotlin/com/example/baseball/logging/LogTraceAspect.kt:37-40`
- Modify: `src/test/kotlin/com/example/baseball/controller/SkillControllerIntegrationTest.kt`
- Modify: `src/test/kotlin/com/example/baseball/controller/SkillControllerCardTest.kt`

**Interfaces:**
- Produces:
  - `ChatIdentity(appUserId: String, botUserKey: String, botKey: String)` — data class, 패키지 `com.example.baseball.dto`
  - `ChatIdentity.Companion.from(request: SkillRequest): ChatIdentity`
  - `ChatIdentity.Companion.fromOrNull(request: SkillRequest): ChatIdentity?`
  - `MissingAppUserIdException : RuntimeException`
  - `SkillRequest.UserProperties(appUserId: String?, botUserKey: String?)`
  - `SkillRequest.ChatProperties(botGroupKey: String?)`

---

- [ ] **Step 1: `ChatIdentityTest` 를 먼저 작성한다 (실패하는 테스트)**

`src/test/kotlin/com/example/baseball/dto/ChatIdentityTest.kt`:

```kotlin
package com.example.baseball.dto

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("ChatIdentity.from - 카카오 식별자 확정")
class ChatIdentityTest {

    private fun request(
        userId: String = "user-id",
        appUserId: String? = "app-1",
        botUserKey: String? = "buk-1",
        botGroupKey: String? = "bot-1",
        withProperties: Boolean = true,
        withChat: Boolean = true,
    ) = SkillRequest(
        userRequest = SkillRequest.UserRequest(
            utterance = "시작",
            user = SkillRequest.User(
                id = userId,
                properties = if (withProperties) {
                    SkillRequest.UserProperties(appUserId = appUserId, botUserKey = botUserKey)
                } else null,
            ),
            chat = if (withChat) {
                SkillRequest.Chat(properties = SkillRequest.ChatProperties(botGroupKey = botGroupKey))
            } else null,
        ),
    )

    @Test
    @DisplayName("properties 에 값이 다 있으면 그대로 쓴다")
    fun usesProperties() {
        val id = ChatIdentity.from(request())

        assertEquals("app-1", id.appUserId)
        assertEquals("buk-1", id.botUserKey)
        assertEquals("bot-1", id.botKey)
    }

    @Test
    @DisplayName("properties.botUserKey 가 없으면 user.id 로 폴백한다")
    fun fallsBackToUserId() {
        val id = ChatIdentity.from(request(userId = "user-id", botUserKey = null))

        assertEquals("user-id", id.botUserKey)
    }

    @Test
    @DisplayName("properties 블록 자체가 없으면 appUserId 를 만들 수 없어 예외")
    fun noPropertiesBlockThrows() {
        assertThrows(MissingAppUserIdException::class.java) {
            ChatIdentity.from(request(withProperties = false))
        }
    }

    @Test
    @DisplayName("chat 이 없으면(1:1 채팅) botUserKey 를 botKey 로 쓴다")
    fun fallsBackToBotUserKeyAsRoom() {
        val id = ChatIdentity.from(request(withChat = false))

        assertEquals("buk-1", id.botKey)
        assertEquals("buk-1", id.botUserKey)
    }

    @Test
    @DisplayName("botGroupKey 만 없어도 botUserKey 를 botKey 로 쓴다")
    fun fallsBackWhenBotGroupKeyMissing() {
        val id = ChatIdentity.from(request(botGroupKey = null))

        assertEquals("buk-1", id.botKey)
    }

    @Test
    @DisplayName("appUserId 가 없으면 MissingAppUserIdException — 폴백하지 않는다")
    fun missingAppUserIdThrows() {
        assertThrows(MissingAppUserIdException::class.java) {
            ChatIdentity.from(request(appUserId = null))
        }
    }

    @Test
    @DisplayName("fromOrNull 은 appUserId 가 없으면 null 을 준다 (로깅 aspect 용)")
    fun fromOrNullSwallows() {
        assertNull(ChatIdentity.fromOrNull(request(appUserId = null)))
        assertEquals("app-1", ChatIdentity.fromOrNull(request())?.appUserId)
    }
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.dto.ChatIdentityTest"`
Expected: FAIL — `Unresolved reference: ChatIdentity`, `Unresolved reference: UserProperties` 등 컴파일 에러

- [ ] **Step 3: `SkillRequest` 를 nullable 필드 구조로 교체한다**

`src/main/kotlin/com/example/baseball/dto/SkillRequest.kt` 전체를 아래로 교체:

```kotlin
package com.example.baseball.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 카카오 오픈빌더 스킬 요청. 실제 페이로드는 훨씬 크지만 필요한 필드만 매핑한다.
 *
 * 식별자 필드를 전부 nullable 로 두는 건 의도적이다. non-null 로 못 박으면 값이 빠진 페이로드 한 건에
 * Jackson 역직렬화가 실패해 500 이 나가는데, 이건 SkillExceptionHandler 진입 전이라 안내 응답으로
 * 못 바꾼다(= 방 전체가 먹통). non-null 확정은 ChatIdentity.from 이 전담한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SkillRequest(
    val userRequest: UserRequest,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserRequest(
        @field:Schema(description = "사용자 발화", example = "1234")
        val utterance: String,
        val user: User,
        val chat: Chat? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class User(
        @field:Schema(
            description = "카카오 사용자 ID. user.type 이 botUserKey 면 botUserKey 와 같은 값.",
            example = "test-user-001",
        )
        val id: String,
        val properties: UserProperties? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserProperties(
        @field:Schema(description = "카카오 앱 전역 사용자 키", example = "app-user-001")
        val appUserId: String? = null,
        @field:Schema(description = "카카오 챗봇 기준 사용자 키", example = "test-user-001")
        val botUserKey: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Chat(
        val properties: ChatProperties? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatProperties(
        @field:Schema(description = "그룹 챗봇 채팅방 ID = botKey", example = "test-bot-001")
        val botGroupKey: String? = null,
    )
}
```

- [ ] **Step 4: `ChatIdentity` 를 구현한다**

`src/main/kotlin/com/example/baseball/dto/ChatIdentity.kt`:

```kotlin
package com.example.baseball.dto

import com.example.baseball.common.TraceKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * 카카오가 appUserId 를 보내지 않은 요청.
 *
 * 폴백으로 때우지 않는 이유: appUserId 없이 botUserKey 로 전역 유저를 만들면 같은 사람이 방마다 다른
 * User 행으로 갈려 방마다 다른 점수가 노출된다(점수는 언제나 전역 User.score 기준이어야 한다).
 * IllegalArgument/IllegalState 를 상속하지 않는 것도 의도적이다 — LogTraceAspect 가 그 둘만
 * REJECTED(정상 유저 흐름)로 분류하므로, 별도 타입이라야 ERROR 로 남아 알람에 잡힌다.
 */
class MissingAppUserIdException :
    RuntimeException("카카오 appUserId 가 요청에 없습니다. 채널 앱키 연동을 확인하세요.")

/** 요청에서 뽑아낸 세 식별자의 non-null 확정본. 폴백 규칙은 [from] 한 곳에만 존재한다. */
data class ChatIdentity(
    val appUserId: String,
    val botUserKey: String,
    val botKey: String,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ChatIdentity::class.java)

        /** @throws MissingAppUserIdException appUserId 가 요청에 없을 때 */
        fun from(request: SkillRequest): ChatIdentity {
            val user = request.userRequest.user
            val appUserId = user.properties?.appUserId ?: throw MissingAppUserIdException()
            val botUserKey = user.properties?.botUserKey ?: fallback("botUserKey", user.id)
            val botKey = request.userRequest.chat?.properties?.botGroupKey
                ?: fallback("botKey", botUserKey)

            return ChatIdentity(appUserId = appUserId, botUserKey = botUserKey, botKey = botKey)
        }

        /** 로깅 aspect 전용. aspect 안에서 예외가 나면 요청 자체가 죽으므로 여기서만 삼킨다. */
        fun fromOrNull(request: SkillRequest): ChatIdentity? =
            try {
                from(request)
            } catch (e: MissingAppUserIdException) {
                null
            }

        private fun fallback(field: String, value: String): String {
            log.warn("evt=identity_fallback field={} traceId={}", field, MDC.get(TraceKeys.TRACE_ID))
            return value
        }
    }
}
```

- [ ] **Step 5: `ChatIdentityTest` 통과를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.dto.ChatIdentityTest"`
Expected: PASS — 7 tests

- [ ] **Step 6: `SkillController.play` 를 `ChatIdentity` 로 배선한다**

`SkillController.kt` 의 `play` 본문(현재 `:62-69`)을 아래로 교체:

```kotlin
    @PostMapping("/skill/play")
    fun play(@RequestBody request: SkillRequest): SkillResponse {
        val identity = ChatIdentity.from(request)
        // 멘션 프리필 버튼이 심는 제로폭 공백(U+200B 등)을 제거한 뒤 판정한다.
        // 이게 남으면 "​1234"가 숫자 판정(all isDigit)을 통과하지 못해 추측이 먹히지 않는다.
        val utterance = request.userRequest.utterance.replace(zeroWidthChars, "").trim()
        return handle(identity, utterance)
    }
```

import 에 `com.example.baseball.dto.ChatIdentity` 를 추가한다.

- [ ] **Step 7: `handle` 의 시그니처와 호출 4곳을 바꾼다**

`handle` 의 본문은 건드리지 않고 아래 5줄만 정확히 치환한다. `GameService` 는 아직 옛 시그니처이므로 값을 풀어서 넘긴다(Task 2 에서 정리된다).

| 현재 | 교체 후 |
|---|---|
| `private fun handle(appUserId: String, botKey: String, utterance: String): SkillResponse =` | `private fun handle(identity: ChatIdentity, utterance: String): SkillResponse =` |
| `gameService.startGame(appUserId, botKey)` | `gameService.startGame(identity.appUserId, identity.botKey)` |
| `val answer = gameService.giveUp(appUserId, botKey)` | `val answer = gameService.giveUp(identity.appUserId, identity.botKey)` |
| `SkillCommand.RANKING -> formatRanking(botKey)` | `SkillCommand.RANKING -> formatRanking(identity.botKey)` |
| `SkillCommand.GUESS -> formatGuess(gameService.guess(appUserId, botKey, utterance))` | `SkillCommand.GUESS -> formatGuess(gameService.guess(identity.appUserId, identity.botKey, utterance))` |

- [ ] **Step 8: `formatRanking` 의 null 분기를 제거한다**

`formatRanking` 의 KDoc 첫 줄과 시그니처·첫 두 줄(현재 `:244-251`)을 아래로 교체. 이후 본문(`val ranking = ...` 부터)은 그대로 둔다:

```kotlin
    /**
     * 봇(채팅방) 랭킹 TOP 10. 빈 랭킹은 안내 메시지로 변환.
     * 각 줄의 사용자 이름은 "{{#mentions.userN}}" 자리표시자로 두고, extra.mentions 에 botUserKey 를 등록해
     * 카카오가 실제 닉네임(@사용자) 멘션으로 치환하게 한다(STEP 12 배포 피드백 — 원시 키 노출 제거).
     */
    private fun formatRanking(botKey: String): SkillResponse {
        val ranking = rankingService.getBotRanking(botKey)
```

즉 아래 두 줄을 삭제한다:

```kotlin
        if (botKey == null) return SkillResponse.text("채팅방 정보를 확인할 수 없어 랭킹을 보여줄 수 없습니다.")

```

- [ ] **Step 9: `SkillExceptionHandler` 에 핸들러를 추가한다**

`SkillExceptionHandler.kt` 의 `handleIllegalArgument` 아래에 추가:

```kotlin
    /** appUserId 없는 요청. 전역 정체성을 만들 수 없어 게임을 진행하지 않는다. */
    @ExceptionHandler(MissingAppUserIdException::class)
    fun handleMissingAppUserId(e: MissingAppUserIdException): SkillResponse =
        guide("일시적인 문제가 발생했어요. 잠시 후 다시 시도해주세요.")
```

import 에 `com.example.baseball.dto.MissingAppUserIdException` 을 추가한다.

- [ ] **Step 10: `LogTraceAspect` 의 식별자 추출을 통일한다**

`LogTraceAspect.kt:37-40` 을 아래로 교체:

```kotlin
        val request = joinPoint.args.firstOrNull() as? SkillRequest
        val identity = request?.let { ChatIdentity.fromOrNull(it) }
        val botKey = identity?.botKey ?: "-"
        val botUserKey = identity?.botUserKey ?: request?.userRequest?.user?.id ?: "-"
        val utterance = request?.userRequest?.utterance?.trim().orEmpty()
```

import 에 `com.example.baseball.dto.ChatIdentity` 를 추가한다.

클래스 KDoc 의 동작 순서 1번 항목을 아래로 고친다:

```
 *  1. 인자(SkillRequest)에서 ChatIdentity 로 botKey/botUserKey 추출, utterance 로 intent 분류.
 *     appUserId 부재 예외는 여기서 삼킨다 — 로깅이 요청을 죽이면 안 된다.
```

- [ ] **Step 11: `SkillControllerCardTest` 의 페이로드 헬퍼를 교체한다**

`SkillControllerCardTest.kt:32-33` 을 아래로 교체:

```kotlin
    private fun body(utterance: String, userId: String): String =
        """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId","properties":{"appUserId":"app-$userId","botUserKey":"$userId"}},"chat":{"properties":{"botGroupKey":"bot-$userId"}}}}"""
```

게임을 조회하는 2곳(`:68`, `:93`)의 첫 인자를 방 키로 바꾼다:

```kotlin
        val answer = gameRepository.findFirstByBotKeyAndStatus("bot-$userId", GameStatus.PLAYING)!!.answer
```

> 메서드 이름은 이 태스크에서 아직 `findFirstByBotKeyAndStatus` 다. Task 2 에서 `findFirstByBotKeyAndStatusOrderByIdDesc` 로 바뀐다.

- [ ] **Step 12: `SkillControllerIntegrationTest` 의 페이로드·방 키를 교체한다**

`SkillControllerIntegrationTest.kt` 의 `body`/`play` 헬퍼(현재 `:30-46`)를 아래로 교체:

```kotlin
    /**
     * 카카오 스킬 요청 본문. 게임이 방 단위라 botKey 를 고정하면 테스트끼리 서로의 게임을 끊는다.
     * 그래서 방 키를 유저별로 파생시켜 기존의 테스트 간 격리를 그대로 유지한다.
     */
    private fun body(
        utterance: String,
        userId: String,
        botKey: String = roomOf(userId),
    ): String =
        """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId","properties":{"appUserId":"app-$userId","botUserKey":"$userId"}},"chat":{"properties":{"botGroupKey":"$botKey"}}}}"""

    private fun roomOf(userId: String) = "bot-$userId"

    private fun play(
        utterance: String,
        userId: String,
        expectedText: String,
        botKey: String = roomOf(userId),
    ) {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(utterance, userId, botKey)
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value("2.0") }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString(expectedText)) }
        }
    }
```

`seedBotUser` 는 그대로 둔다.

게임을 조회하는 5곳의 첫 인자를 방 키로 바꾼다:

| 위치 | 테스트 | 교체 후 첫 인자 |
|---|---|---|
| `:63` | `startGuessWin` | `roomOf(userId)` |
| `:79` | `guessStripsZeroWidthPrefix` | `roomOf(userId)` |
| `:97` | `winRevealsAnswerWithoutMention` | `botKey` (그 테스트의 지역 변수를 그대로) |
| `:116` | `startWrongGuess` | `roomOf(userId)` |
| `:132` | `startGiveUp` | `roomOf(userId)` |

`"랭킹: bot 정보가 없으면 안내 메시지"` 테스트(현재 `:206-210`)를 통째로 삭제한다 — `botKey` 는 이제 항상 존재하므로 검증 대상이 사라졌다:

```kotlin
    @Test
    @DisplayName("랭킹: bot 정보가 없으면 안내 메시지")
    fun rankingNoBotKey() {
        play("랭킹", "any-user", "채팅방 정보를 확인할 수 없어")
    }
```

- [ ] **Step 13: 전체 테스트를 돌려 초록을 확인한다**

Run: `./gradlew test`
Expected: PASS — 실패 0건, 총 123개 (기존 117 − 랭킹 테스트 1 + `ChatIdentityTest` 7)

- [ ] **Step 14: 변경 보고 후 리뷰 요청**

변경 파일 목록과 테스트 결과를 보고하고 멈춘다. **커밋하지 않는다.** 사용자가 위임하면:

```bash
git add src/main/kotlin/com/example/baseball/dto/ src/main/kotlin/com/example/baseball/controller/ src/main/kotlin/com/example/baseball/logging/ src/test/kotlin/com/example/baseball/dto/ src/test/kotlin/com/example/baseball/controller/
git commit -m "feat: ChatIdentity 로 카카오 식별자 3종을 non-null 확정"
```

---

### Task 2: 서비스 계층을 `ChatIdentity` 기반 방 단위로 전환

`GameService` 와 `UserService` 를 함께 바꾼다 — `GameService` 가 `UserService` 를 호출하므로 따로 바꾸면 중간 상태가 컴파일되지 않는다.

**Files:**
- Modify: `src/main/kotlin/com/example/baseball/domain/game/GameRepository.kt` (전체 교체)
- Modify: `src/main/kotlin/com/example/baseball/domain/game/Game.kt:14-20` (클래스 주석)
- Modify: `src/main/kotlin/com/example/baseball/service/GameService.kt` (전체 교체)
- Modify: `src/main/kotlin/com/example/baseball/service/UserService.kt:49-92`
- Modify: `src/main/kotlin/com/example/baseball/controller/SkillController.kt` (호출 3곳)
- Modify: `src/test/kotlin/com/example/baseball/service/GameServiceTest.kt` (전체 교체)
- Modify: `src/test/kotlin/com/example/baseball/service/UserServiceTest.kt`
- Modify: `src/test/kotlin/com/example/baseball/controller/SkillControllerCardTest.kt` (조회 2곳)
- Modify: `src/test/kotlin/com/example/baseball/controller/SkillControllerIntegrationTest.kt` (조회 5곳)

**Interfaces:**
- Consumes: `ChatIdentity(appUserId, botUserKey, botKey)` — Task 1
- Produces:
  - `StartOutcome(game: Game, replacedAnswer: String?)` (패키지 `com.example.baseball.service`)
  - `GameService.startGame(id: ChatIdentity, gameDifficulty: GameDifficulty = GameDifficulty.NORMAL): StartOutcome`
  - `GameService.guess(id: ChatIdentity, guess: String): GuessOutcome`
  - `GameService.giveUp(id: ChatIdentity): String`
  - `UserService.register(id: ChatIdentity)`
  - `UserService.accrue(id: ChatIdentity, gain: Int): Int`
  - `GameRepository.findAllByBotKeyAndStatus(botKey: String, status: GameStatus): List<Game>`
  - `GameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(botKey: String, status: GameStatus): Game?`

---

- [ ] **Step 1: `GameServiceTest` 를 새 시그니처로 다시 쓴다 (실패하는 테스트)**

`src/test/kotlin/com/example/baseball/service/GameServiceTest.kt` 전체를 아래로 교체:

```kotlin
package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameDifficulty
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import com.example.baseball.dto.ChatIdentity
import io.mockk.CapturingSlot
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameServiceTest {

    private val gameRepository = mockk<GameRepository>()
    // 적립은 UserService 책임이므로 여기서는 호출 위임만 검증한다(상호작용 테스트).
    private val userService = mockk<UserService>(relaxed = true)
    private val sut = GameService(gameRepository, userService)

    private val identity = ChatIdentity(appUserId = "app-1", botUserKey = "buk-1", botKey = "bot-1")

    private fun captureSave(): CapturingSlot<Game> {
        val slot = slot<Game>()
        every { gameRepository.save(capture(slot)) } answers { slot.captured }
        return slot
    }

    /** 방의 진행중 게임을 세팅한다. 마지막 인자가 "최신" 게임으로 취급된다. */
    private fun playingGames(vararg games: Game) {
        every {
            gameRepository.findAllByBotKeyAndStatus(identity.botKey, GameStatus.PLAYING)
        } returns games.toList()
        every {
            gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(identity.botKey, GameStatus.PLAYING)
        } returns games.lastOrNull()
    }

    private fun game(answer: String) = Game(botKey = identity.botKey, answer = answer)

    @Nested
    @DisplayName("startGame")
    inner class StartGame {

        private fun assertValidAnswer(answer: String, difficulty: GameDifficulty) {
            assertEquals(4, answer.length, "정답은 4자리여야 한다")
            assertEquals(answer.length, answer.toSet().size, "정답에 중복 기호가 없어야 한다")
            val allowed = difficulty.symbols.toSet()
            assertTrue(answer.all { it in allowed }, "정답은 허용 기호만 사용해야 한다: $answer")
        }

        @Test
        @DisplayName("NORMAL: 0~9 숫자 4자리·score=100·PLAYING 게임을 방 키로 저장한다")
        fun createsNormalGame() {
            playingGames()
            val slot = captureSave()

            val outcome = sut.startGame(identity, gameDifficulty = GameDifficulty.NORMAL)

            val saved = slot.captured
            assertEquals(outcome.game, saved)
            assertNull(outcome.replacedAnswer)
            assertEquals("bot-1", saved.botKey)
            assertEquals(GameDifficulty.NORMAL, saved.gameDifficulty)
            assertEquals(100, saved.score)
            assertEquals(0, saved.tries)
            assertEquals(GameStatus.PLAYING, saved.status)
            assertNull(saved.finishedAt)
            assertValidAnswer(saved.answer, GameDifficulty.NORMAL)
            assertTrue(saved.answer.all { it.isDigit() }, "NORMAL 정답은 전부 숫자")
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("시작 시 참가자(User/BotUser)를 register 로 미리 보장한다")
        fun registersParticipantOnStart() {
            playingGames()
            captureSave()

            sut.startGame(identity)

            verify(exactly = 1) { userService.register(identity) }
        }

        @Test
        @DisplayName("HARD: 0~9+a~e 기호 4자리·score=200 게임을 저장한다")
        fun createsHardGame() {
            playingGames()
            val slot = captureSave()

            sut.startGame(identity, gameDifficulty = GameDifficulty.HARD)

            val saved = slot.captured
            assertEquals(GameDifficulty.HARD, saved.gameDifficulty)
            assertEquals(200, saved.score)
            assertValidAnswer(saved.answer, GameDifficulty.HARD)
            val allowed = (('0'..'9') + ('a'..'e')).toSet()
            assertTrue(saved.answer.all { it in allowed })
        }

        @Test
        @DisplayName("EASY: 0~5 숫자 4자리·score=50 게임을 저장한다")
        fun createsEasyGame() {
            playingGames()
            val slot = captureSave()

            sut.startGame(identity, gameDifficulty = GameDifficulty.EASY)

            val saved = slot.captured
            assertEquals(GameDifficulty.EASY, saved.gameDifficulty)
            assertEquals(50, saved.score)
            assertValidAnswer(saved.answer, GameDifficulty.EASY)
            val allowed = ('0'..'5').toSet()
            assertTrue(saved.answer.all { it in allowed })
        }

        @Test
        @DisplayName("방에 진행중 게임이 있으면 GIVEUP 처리하고 그 정답을 replacedAnswer 로 알린다")
        fun abandonsExistingGame() {
            val existing = game("1234")
            playingGames(existing)
            val slot = captureSave()

            val outcome = sut.startGame(identity)

            assertEquals(GameStatus.GIVEUP, existing.status)
            assertEquals("1234", outcome.replacedAnswer)
            assertEquals(GameStatus.PLAYING, slot.captured.status)
            assertNotEquals(existing, slot.captured)
        }

        @Test
        @DisplayName("동시 시작으로 유령 게임이 2건 남아 있으면 전부 GIVEUP 처리한다")
        fun abandonsAllGhostGames() {
            val older = game("1234")
            val newer = game("5678")
            playingGames(older, newer)
            captureSave()

            sut.startGame(identity)

            assertEquals(GameStatus.GIVEUP, older.status)
            assertEquals(GameStatus.GIVEUP, newer.status)
        }
    }

    @Nested
    @DisplayName("guess")
    inner class Guess {

        @Test
        @DisplayName("정답을 맞히면 WON으로 종료되고 finished=true")
        fun correctGuessWins() {
            val g = game("5273")
            playingGames(g)

            val outcome = sut.guess(identity, "5273")

            assertTrue(outcome.result.isWin)
            assertTrue(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.WON, g.status)
        }

        @Test
        @DisplayName("유령 게임이 2건이면 최신 1건으로 판정한다")
        fun usesLatestGame() {
            val older = game("1234")
            val newer = game("5678")
            playingGames(older, newer)

            val outcome = sut.guess(identity, "5678")

            assertTrue(outcome.result.isWin)
            assertEquals(GameStatus.WON, newer.status)
            assertEquals(GameStatus.PLAYING, older.status)
        }

        @Test
        @DisplayName("정답 시 gain 적립 위임 후 적립 점수로 상위% 조회 결과를 outcome 에 싣는다")
        fun winDelegatesAccrualAndPercentile() {
            playingGames(game("5273")) // NORMAL, 1번에 정답 → gain = 95
            every { userService.accrue(identity, 95) } returns 1095
            every { userService.percentileOf(1095) } returns Percentile(rank = 5, total = 100, topPercent = 5)

            val outcome = sut.guess(identity, "5273")

            assertEquals(95, outcome.gain)
            assertEquals(1095, outcome.totalScore)
            assertEquals(Percentile(5, 100, 5), outcome.percentile)
            verify(exactly = 1) { userService.accrue(identity, 95) }
            verify(exactly = 1) { userService.percentileOf(1095) }
            confirmVerified(userService)
        }

        @Test
        @DisplayName("오답이면 PLAYING 유지·시도수만 증가하고 적립은 호출되지 않는다")
        fun wrongGuessContinues() {
            val g = game("5273")
            playingGames(g)

            val outcome = sut.guess(identity, "1289") // 1S 0B

            assertFalse(outcome.result.isWin)
            assertFalse(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.PLAYING, g.status)
            assertEquals(0, outcome.gain)
            assertEquals(0, outcome.totalScore)
            verify(exactly = 0) { userService.accrue(any(), any()) }
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            playingGames()

            assertThrows(IllegalStateException::class.java) { sut.guess(identity, "1234") }
        }

        @Test
        @DisplayName("형식이 잘못된 입력은 예외이며 시도 횟수가 증가하지 않는다")
        fun invalidInputDoesNotConsumeTry() {
            val g = game("5273")
            playingGames(g)

            assertThrows(IllegalArgumentException::class.java) { sut.guess(identity, "5523") }
            assertEquals(0, g.tries)
            assertEquals(GameStatus.PLAYING, g.status)
        }
    }

    @Nested
    @DisplayName("giveUp")
    inner class GiveUp {

        @Test
        @DisplayName("포기하면 GIVEUP으로 종료되고 정답을 반환한다")
        fun giveUpReturnsAnswer() {
            val g = game("5273")
            playingGames(g)

            assertEquals("5273", sut.giveUp(identity))
            assertEquals(GameStatus.GIVEUP, g.status)
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            playingGames()

            assertThrows(IllegalStateException::class.java) { sut.giveUp(identity) }
        }
    }
}
```

- [ ] **Step 2: `UserServiceTest` 를 새 시그니처로 고친다**

파일 상단 import 에 추가:

```kotlin
import com.example.baseball.dto.ChatIdentity
```

`sut` 선언 아래에 공용 픽스처를 추가:

```kotlin
    private val identity = ChatIdentity(appUserId = "app-1", botUserKey = "u1", botKey = "bot-1")
```

호출부를 아래처럼 치환한다:

| 테스트 | 현재 | 교체 후 |
|---|---|---|
| `addsToExistingUser` | `sut.accrue(appUserId = "app-1", botKey = null, botUserKey = "u1", gain = 65)` | `sut.accrue(identity, gain = 65)` |
| `accruesBotUserToo` | `sut.accrue(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1", gain = 30)` | `sut.accrue(identity, gain = 30)` |
| `createsBotUserWhenMissing` | `sut.accrue(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1", gain = 40)` | `sut.accrue(identity, gain = 40)` |
| `rejectsNegativeGain` | `sut.accrue(appUserId = "app-1", botKey = null, botUserKey = "u1", gain = -1)` | `sut.accrue(identity, gain = -1)` |
| `createsBothWhenMissing` | `sut.register(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1")` | `sut.register(identity)` |
| `noCreateWhenExisting` | `sut.register(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1")` | `sut.register(identity)` |

`addsToExistingUser` 와 `rejectsNegativeGain` 은 이제 `BotUser` 도 건드리므로 스텁이 필요하다. `addsToExistingUser` 를 아래로 교체:

```kotlin
        @Test
        @DisplayName("기존 유저가 있으면 생성 없이 score 에 gain 을 더하고 누적값을 반환한다")
        fun addsToExistingUser() {
            val user = User(appUserId = "app-1").apply { score = 1000 }
            val botUser = BotUser(user = user, botUserKey = "u1", botKey = "bot-1", score = 0)
            every { userRepository.findByAppUserId("app-1") } returns user
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            val total = sut.accrue(identity, gain = 65)

            assertEquals(1065, total)
            assertEquals(1065, user.score)
            verify(exactly = 0) { userRepository.save(any()) } // 더티체킹 → 명시 save 불필요
        }
```

`rejectsNegativeGain` 은 `require` 가 먼저 터져 리포지터리에 닿지 않으므로 그대로 둔다.

`createsWhenMissing` 을 아래로 교체(`app-new` 를 쓰므로 지역 identity 가 필요하다):

```kotlin
        @Test
        @DisplayName("유저가 없으면 새로 만들어 적립한다(getOrCreate)")
        fun createsWhenMissing() {
            val newIdentity = ChatIdentity(appUserId = "app-new", botUserKey = "u1", botKey = "bot-1")
            every { userRepository.findByAppUserId("app-new") } returns null
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns null
            stubSaves()

            val saved = slot<User>()
            every { userRepository.save(capture(saved)) } answers { saved.captured }

            val total = sut.accrue(newIdentity, gain = 50)

            assertEquals("app-new", saved.captured.appUserId)
            assertEquals(50, total)
        }
```

**삭제할 테스트 2개** — `botKey` 가 null 일 수 없으므로 검증 대상이 사라졌다:
- `BotUserScope.skipsBotUserWhenNoBotKey` (현재 `:99-109`)
- `Register.globalOnlyWhenNoBotKey` (현재 `:158-168`)

- [ ] **Step 3: 컴파일 실패를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.service.*"`
Expected: FAIL — `Unresolved reference: findAllByBotKeyAndStatus`, `startGame`/`accrue`/`register` 인자 타입 불일치

- [ ] **Step 4: `GameRepository` 를 방 단위 쿼리로 교체한다**

`src/main/kotlin/com/example/baseball/domain/game/GameRepository.kt` 전체를 아래로 교체:

```kotlin
package com.example.baseball.domain.game

import org.springframework.data.jpa.repository.JpaRepository

interface GameRepository : JpaRepository<Game, Long> {

    /**
     * 방의 진행중 게임 전부. 정상 상태에선 0 또는 1건이지만 동시 시작으로 2건 이상 남을 수 있어
     * 리스트로 받아 startGame 이 전건 정리한다(방당 1건 불변식은 DB 가 아니라 거기서 수렴한다).
     */
    fun findAllByBotKeyAndStatus(botKey: String, status: GameStatus): List<Game>

    /** 방의 진행중 게임 중 최신 1건. 유령이 남아 있어도 가장 최근 게임으로 판정한다. */
    fun findFirstByBotKeyAndStatusOrderByIdDesc(botKey: String, status: GameStatus): Game?
}
```

- [ ] **Step 5: `Game` 의 낡은 클래스 주석을 고친다**

`Game.kt:14-20` 의 KDoc 을 아래로 교체(`bot_key` 에 `botKey ?: appUserId` 가 들어간다는 서술이 이제 거짓이다):

```kotlin
/**
 * 한 판의 숫자야구 게임 세션. 채팅방(botKey) 단위로 스코프된다 — 방 하나당 진행중 게임 하나.
 *
 * 상태 전이(승리/포기)는 외부에서 status 를 직접 바꾸지 않고 도메인 메서드로만 수행한다.
 * → 항상 finishedAt 이 함께 채워져 데이터 정합성이 깨지지 않는다.
 */
```

`indexes` 의 `idx_game_bot_key_status` 는 두 쿼리 모두가 쓰므로 그대로 둔다.

- [ ] **Step 6: `GameService` 를 교체한다**

`src/main/kotlin/com/example/baseball/service/GameService.kt` 전체를 아래로 교체:

```kotlin
package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameDifficulty
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import com.example.baseball.dto.ChatIdentity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 새 게임 시작 결과.
 *
 * @property replacedAnswer 이번 시작으로 강제 종료된 직전 게임의 정답(없으면 null).
 *   방 단위라 남의 게임을 끊을 수 있어, 무엇이 종료됐는지 응답에 밝히는 데 쓴다.
 */
data class StartOutcome(
    val game: Game,
    val replacedAnswer: String?,
)

/**
 * 추측 1회의 결과(컨트롤러가 응답 메시지를 만들 때 사용).
 * gain/totalScore 는 승리 시에만 채워지고 오답·오류 시에는 0 으로 둔다.
 */
data class GuessOutcome(
    val result: JudgeResult,
    val tries: Int,
    val finished: Boolean,
    val gain: Int = 0,
    /** 적립 후 전역 누적 점수. 응답의 "이전 → 현재" 표기에 쓴다. */
    val totalScore: Int = 0,
    /** 적립 후 전역 상위 백분위(표본 부족이면 null). */
    val percentile: Percentile? = null,
    val answer: String? = null,
)

@Service
class GameService(
    private val gameRepository: GameRepository,
    private val userService: UserService,
) {
    /**
     * 새 게임 시작. 방에 진행 중인 게임이 있으면 전부 포기 처리한다.
     *
     * "방당 진행중 게임 1건" 을 DB 제약으로 걸지 않는 이유: UNIQUE(bot_key, status) 는 한 방의
     * 두 번째 WON 게임에서 유니크 위반이 나고, MySQL 엔 부분 유니크 인덱스가 없다. 대신 여기서
     * 전건 정리해 수렴시킨다(설계 문서 D6).
     *
     * 시작 시점에 참가자(User/BotUser) 행을 미리 보장해, 승리 전에도 참가자가 추적되게 한다.
     */
    @Transactional
    fun startGame(
        id: ChatIdentity,
        gameDifficulty: GameDifficulty = GameDifficulty.NORMAL,
    ): StartOutcome {
        userService.register(id)

        val replaced = gameRepository.findAllByBotKeyAndStatus(id.botKey, GameStatus.PLAYING)
        replaced.forEach { it.giveUp() }

        val game = gameRepository.save(
            Game(
                botKey = id.botKey,
                answer = generateAnswer(gameDifficulty),
                gameDifficulty = gameDifficulty,
            )
        )
        return StartOutcome(game = game, replacedAnswer = replaced.firstOrNull()?.answer)
    }

    /**
     * 추측 처리. 승리 시 같은 트랜잭션에서 점수를 산정·적립해 게임 종료와 적립이 원자적으로 커밋된다.
     *
     * 점수는 게임을 시작한 사람이 아니라 [id] 즉 맞힌 사람에게 간다.
     *
     * @throws IllegalStateException 진행 중인 게임이 없을 때
     * @throws IllegalArgumentException 입력이 규칙에 맞지 않을 때(BaseballJudge 가 검증)
     */
    @Transactional
    fun guess(id: ChatIdentity, guess: String): GuessOutcome {
        val game = currentGame(id.botKey)

        // 검증 실패 시 여기서 예외 → 시도 횟수는 증가하지 않는다(잘못된 입력은 차감 안 함).
        val result = BaseballJudge.judge(game.answer, guess)

        game.recordTry()

        if (!result.isWin) {
            return GuessOutcome(result = result, tries = game.tries, finished = false)
        }

        game.win()
        val gain = ScoreCalculator.gain(game.tries, game.gameDifficulty)
        val totalScore = userService.accrue(id, gain)
        val percentile = userService.percentileOf(totalScore) // 적립 후 점수 기준
        return GuessOutcome(
            result = result, tries = game.tries, finished = true,
            gain = gain, totalScore = totalScore, percentile = percentile,
            answer = game.answer,
        )
    }

    @Transactional
    fun giveUp(id: ChatIdentity): String {
        val game = currentGame(id.botKey)
        game.giveUp()
        return game.answer
    }

    private fun currentGame(botKey: String): Game =
        gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(botKey, GameStatus.PLAYING)
            ?: throw IllegalStateException("진행 중인 게임이 없습니다. '시작'을 입력해 새 게임을 시작하세요.")

    private fun generateAnswer(gameDifficulty: GameDifficulty): String =
        gameDifficulty.symbols.shuffled().take(DIGITS).joinToString("")

    companion object {
        /** 게임 자릿수는 4자리로 고정. 난이도는 자릿수가 아니라 후보 기호 집합만 늘린다. */
        const val DIGITS = 4
    }
}
```

- [ ] **Step 7: `UserService` 의 `accrue`/`register` 를 교체한다**

`UserService.kt` 의 `accrue` KDoc + 본문과 `register` KDoc + 본문(현재 `:49-92`)을 아래로 교체:

```kotlin
    /**
     * 승리 시 획득 점수(gain)를 전역 유저(User)와 봇 내 유저(BotUser)에 함께 누적한다.
     * 같은 트랜잭션에서 더티체킹으로 flush 되어 게임 상태 전이와 원자적으로 커밋된다.
     *
     * @return 적립 후 전역 누적 score(응답의 "이전 → 현재" 표기에 쓴다).
     *
     * 동시성 메모: getOrCreate 는 "조회 후 없으면 생성"이라 같은 유저의 첫 요청 2건이 거의 동시에
     * 들어오면 중복 INSERT 가능성이 이론상 존재한다. 카카오 챗봇은 유저당 발화가 직렬이라 실질
     * 위험은 낮고, users(app_user_id)·bot_users(bot_key, bot_user_key) UNIQUE 제약이 DB 최종
     * 방어선이다. 규모가 커지면 INSERT ... ON DUPLICATE KEY(upsert) 또는 Redis 로 전환한다.
     */
    @Transactional
    fun accrue(id: ChatIdentity, gain: Int): Int {
        require(gain >= 0) { "gain 은 0 이상이어야 합니다. (입력: $gain)" }

        val user = getOrCreateUser(id.appUserId)
        user.score += gain
        getOrCreateBotUser(user, id.botKey, id.botUserKey).score += gain

        return user.score
    }

    /**
     * 게임 시작 시점에 참가자(User/BotUser) 행만 보장한다(점수 변동 없음).
     * 승리해야만 행이 생기던 문제를 막아, 아직 점수가 없는 참여자도 추적·집계에 포함시킨다.
     */
    @Transactional
    fun register(id: ChatIdentity) {
        val user = getOrCreateUser(id.appUserId)
        getOrCreateBotUser(user, id.botKey, id.botUserKey)
    }
```

import 에 `com.example.baseball.dto.ChatIdentity` 를 추가한다.

`getOrCreateUser`/`getOrCreateBotUser` 와 그 주석은 그대로 둔다 — 둘 다 "UNIQUE 제약이 동시성 최종 방어선"이라는 비자명한 정보를 담고 있다.

- [ ] **Step 8: `SkillController` 호출 3곳을 새 시그니처로 맞춘다**

| 현재 | 교체 후 |
|---|---|
| `gameService.startGame(identity.appUserId, identity.botKey)` | `gameService.startGame(identity)` |
| `val answer = gameService.giveUp(identity.appUserId, identity.botKey)` | `val answer = gameService.giveUp(identity)` |
| `formatGuess(gameService.guess(identity.appUserId, identity.botKey, utterance))` | `formatGuess(gameService.guess(identity, utterance))` |

> `startGame` 은 이제 `StartOutcome` 을 반환하지만 이 태스크에서는 반환값을 쓰지 않는다. 교체 안내 문구는 Task 3 에서 붙인다.

- [ ] **Step 9: 테스트의 리포지터리 메서드 이름을 갱신한다**

`SkillControllerCardTest.kt` 2곳, `SkillControllerIntegrationTest.kt` 5곳의
`findFirstByBotKeyAndStatus(` 를 `findFirstByBotKeyAndStatusOrderByIdDesc(` 로 바꾼다.

Run: `grep -rn "findFirstByBotKeyAndStatus(" src/`
Expected: 결과 0건

- [ ] **Step 10: 서비스 테스트 통과를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.service.GameServiceTest" --tests "com.example.baseball.service.UserServiceTest"`
Expected: PASS — `GameServiceTest` 14개, `UserServiceTest` 9개

- [ ] **Step 11: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: PASS — 실패 0건, 총 123개 (Task 1 대비 `GameServiceTest` +2, `UserServiceTest` −2)

- [ ] **Step 12: 변경 보고 후 리뷰 요청**

**커밋하지 않는다.** 위임받으면:

```bash
git add src/main/kotlin/com/example/baseball/domain/game/ src/main/kotlin/com/example/baseball/service/ src/main/kotlin/com/example/baseball/controller/SkillController.kt src/test/kotlin/com/example/baseball/
git commit -m "refactor: 게임 세션을 채팅방 단위로 전환하고 서비스 계층을 ChatIdentity 기반으로 정리"
```

---

### Task 3: 시작 교체 안내 문구 + 방 단위 통합 시나리오

`StartOutcome.replacedAnswer` 를 응답에 노출하고, 방 단위 동작을 통합 테스트로 못 박는다.

**Files:**
- Modify: `src/main/kotlin/com/example/baseball/controller/SkillController.kt` (`handle` 의 START 분기)
- Modify: `src/test/kotlin/com/example/baseball/controller/SkillControllerIntegrationTest.kt` (테스트 3개 추가)

**Interfaces:**
- Consumes: `StartOutcome(game, replacedAnswer)` — Task 2, `ChatIdentity` — Task 1

---

- [ ] **Step 1: 통합 테스트 3종을 먼저 추가한다 (실패하는 테스트)**

`SkillControllerIntegrationTest.kt` 의 `firstValidGuessDifferentFrom` 헬퍼 바로 위에 추가:

```kotlin
    @Test
    @DisplayName("방 단위: 다른 사람이 시작하면 진행중이던 게임을 종료하고 그 정답을 알린다")
    fun startReplacesRoomGame() {
        val room = "it-bot-replace"
        play("시작", "starter", "새 게임", botKey = room)
        val first = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!

        play("시작", "intruder", "진행 중이던 게임(정답 ${first.answer})을 종료했어요", botKey = room)

        assertEquals(GameStatus.GIVEUP, gameRepository.findById(first.id!!).orElseThrow().status)
        val second = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    @DisplayName("방 단위: A가 시작한 게임을 B가 맞히면 점수는 B에게 간다")
    fun winnerGetsScore() {
        val room = "it-bot-winner"
        play("시작", "opener", "새 게임", botKey = room)
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!

        play(game.answer, "finisher", "정답", botKey = room)

        assertEquals(0, userRepository.findByAppUserId("app-opener")!!.score)
        assertTrue(userRepository.findByAppUserId("app-finisher")!!.score > 0)
    }

    @Test
    @DisplayName("appUserId 가 없는 페이로드는 안내 응답만 주고 게임을 만들지 않는다")
    fun missingAppUserIdIsRejected() {
        val room = "it-bot-noappid"
        val payload =
            """{"userRequest":{"utterance":"시작","user":{"id":"no-appid"},"chat":{"properties":{"botGroupKey":"$room"}}}}"""

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("잠시 후 다시 시도")) }
        }

        assertNull(gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING))
    }
```

파일 상단 import 에 추가:

```kotlin
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
```

- [ ] **Step 2: 테스트 실패를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.controller.SkillControllerIntegrationTest"`
Expected: FAIL — `startReplacesRoomGame` 이 응답에서 `"진행 중이던 게임(정답 ...)을 종료했어요"` 를 찾지 못해 실패.
(`winnerGetsScore` 와 `missingAppUserIdIsRejected` 는 Task 1~2 결과로 이미 통과할 수 있다. 회귀 방어로 남긴다.)

- [ ] **Step 3: START 분기에 교체 안내 문구를 붙인다**

`SkillController.handle` 의 `SkillCommand.START` 분기 전체를 아래로 교체:

```kotlin
            SkillCommand.START -> {
                val outcome = gameService.startGame(identity)
                // 방 단위라 남의 게임을 끊을 수 있다. 무엇이 종료됐는지 밝히지 않으면 판이 갑자기 바뀐 것처럼 보인다.
                val text = buildString {
                    outcome.replacedAnswer?.let { appendLine("진행 중이던 게임(정답 $it)을 종료했어요.") }
                    append("새 게임을 시작했습니다. ${GameService.DIGITS}자리 숫자를 맞혀보세요. (예: 1234)")
                }
                cardOrText(
                    image = ResultImage.START,
                    title = "⚾ 새 게임 시작",
                    description = text,
                    buttons = listOf(
                        SkillResponse.Button.message("포기", "포기"),
                        SkillResponse.Button.mentionPrefill("제출"),
                    ),
                    fallbackText = text,
                    // 새 게임 시작 카드만 [포기, 제출]을 한 줄에 가로로 노출한다.
                    buttonLayout = "horizontal",
                )
            }
```

- [ ] **Step 4: 통합 테스트 통과를 확인한다**

Run: `./gradlew test --tests "com.example.baseball.controller.SkillControllerIntegrationTest"`
Expected: PASS

- [ ] **Step 5: 전체 테스트를 돌려 최종 확인한다**

Run: `./gradlew test`
Expected: PASS — 실패 0건, 총 126개

- [ ] **Step 6: 낡은 서술이 남아 있지 않은지 훑는다**

Run: `grep -rn "botKey ?: appUserId\|userId로 키잉\|사용자 단위" src/main/kotlin`
Expected: 결과 0건. 나오면 해당 주석을 방 단위·`ChatIdentity` 기준으로 고친다.

Run: `grep -rn "botKey: String?\|appUserId: String, botKey" src/main/kotlin`
Expected: 결과 0건 (서비스 계층에 nullable botKey 나 String 3연발 시그니처가 남지 않았는지 확인)

- [ ] **Step 7: 변경 보고 후 리뷰 요청**

Task 1~3 전체의 변경 요약과 최종 테스트 결과를 보고하고 멈춘다. **커밋하지 않는다.** 위임받으면:

```bash
git add src/main/kotlin/com/example/baseball/controller/SkillController.kt src/test/kotlin/com/example/baseball/controller/SkillControllerIntegrationTest.kt CLAUDE.md docs/
git commit -m "feat: 방 단위 게임 교체 안내 문구 + 통합 시나리오"
```

> `CLAUDE.md` 는 이 작업 이전에 이미 갱신되어 워킹 트리에 있다(커밋되지 않음). 마지막 커밋에 함께 담거나 별도로 커밋한다.

---

## 완료 기준

- `./gradlew test` 실패 0건 (시작 시점 13건 실패 → 0건)
- `ChatIdentity.from` 이 세 식별자의 유일한 확정 지점이며, `appUserId` 부재는 폴백 없이 `MissingAppUserIdException` → 200 안내 응답 + `ERROR` 로그
- `game.bot_key` 에 진짜 방 키가 들어가고, 방의 진행중 게임은 `startGame` 이 전건 정리
- `UserService`·`GameService`·`SkillController` 에 `botKey` null 분기가 없음
- 점수·랭킹 노출이 전역 `User.score` 기준 (통합 테스트 `winnerGetsScore` 로 확인)
- `Game.kt` 클래스 주석과 `CLAUDE.md` 가 방 단위 규칙을 서술 (CLAUDE.md 는 선행 완료)
