package com.example.baseball.controller

import com.example.baseball.dto.SkillResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 스킬 컨트롤러 예외 → 카카오 안내 응답(HTTP 200, simpleText) 변환.
 *
 * 설계 의도 (9-F 증상 4, B안):
 * - 컨트롤러에서 try/catch 로 예외를 삼키면 LogTraceAspect 가 "성공"으로만 보게 되어 실패율을
 *   측정할 수 없다. 그래서 예외 변환을 컨트롤러 밖(advice)으로 빼서, 컨트롤러는 예외를 그대로
 *   던지고 → aspect 가 예외를 관측(ERROR 로깅) → advice 가 사용자에게는 안내 메시지로 응답한다.
 * - 카카오는 200 + simpleText 를 기대하므로, 사용자 입력 오류도 4xx/5xx 가 아니라 200 안내로 응답한다.
 * - 범위를 SkillController 로 한정해 다른 컨트롤러의 예외까지 가로채지 않는다.
 */
@RestControllerAdvice(assignableTypes = [SkillController::class])
class SkillExceptionHandler(
    // 카드 노출 여부 판단용(비어 있으면 simpleText 폴백). SkillController와 동일 규칙.
    @Value("\${kakao.image-base-url:}") private val imageBaseUrl: String,
    // 안내 카드의 멘션 버튼 라벨(SkillController와 동일 설정).
    @Value("\${kakao.mention-button-label:멘션}") private val mentionButtonLabel: String,
) {

    /** 진행 중 게임 없음 등 상태 오류. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): SkillResponse =
        guide(e.message ?: "게임 상태를 확인할 수 없습니다.")

    /** 자릿수/중복/숫자 외 문자 등 입력 규칙 위반. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): SkillResponse =
        guide(e.message ?: "입력이 올바르지 않습니다.")

    /**
     * 안내 메시지를 [멘션·게임 규칙] 버튼과 함께 TextCard로 응답한다.
     * 카드 미설정(테스트/기본) 환경에선 버튼 없는 simpleText로 폴백한다.
     */
    private fun guide(message: String): SkillResponse {
        if (imageBaseUrl.isBlank()) return SkillResponse.text(message)
        return SkillResponse.textCard(
            SkillResponse.TextCard(
                description = message.take(SkillResponse.TextCard.DESC_MAX),
                buttons = SkillResponse.Button.guideButtons(mentionButtonLabel),
            ),
        )
    }
}
