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
