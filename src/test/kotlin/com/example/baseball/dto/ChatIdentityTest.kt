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
