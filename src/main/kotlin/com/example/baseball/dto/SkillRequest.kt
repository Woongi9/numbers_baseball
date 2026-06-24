package com.example.baseball.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 카카오 오픈빌더 스킬 요청. 실제 페이로드는 훨씬 크지만 필요한 필드만 매핑한다.
 * 모르는 필드는 무시하도록 ignoreUnknown = true (카카오 스펙 변경에도 안 깨짐).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SkillRequest(
    val userRequest: UserRequest,
    // 카카오 페이로드의 최상위 bot.id = 봇(채팅방) 식별자. 랭킹을 봇 단위로 묶는 키.
    // 일부 요청/테스트에는 없을 수 있어 nullable.
    val bot: Bot? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserRequest(
        @field:Schema(description = "사용자 발화", example = "1234")
        val utterance: String,
        val user: User,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Bot(
        @field:Schema(description = "봇(채팅방) ID = botKey", example = "test-bot-001")
        val id: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class User(
        @field:Schema(description = "카카오 사용자 ID", example = "test-user-001")
        val id: String,
    )
}
