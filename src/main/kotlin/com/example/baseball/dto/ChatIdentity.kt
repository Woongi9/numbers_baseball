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

        fun from(request: SkillRequest): ChatIdentity {
            val user = request.userRequest.user
            val botUserKey = user.properties?.botUserKey ?: fallback("botUserKey", user.id)
            // temp: 비즈니스 인증 전이라 appUserId 가 안 온다. 단일 봇이라 botUserKey 가 사실상
            // 안정적 전역 키다. 인증 완료 후 UserService 가 임시행을 진짜 appUserId 로 개명한다.
            // 설계·revert 계획: docs/2026-07-23-botuserkey-temp-identity-design.md
            val appUserId = user.properties?.appUserId ?: botUserKey
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
