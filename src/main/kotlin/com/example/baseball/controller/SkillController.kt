package com.example.baseball.controller

import com.example.baseball.dto.SkillRequest
import com.example.baseball.dto.SkillResponse
import com.example.baseball.service.GameService
import com.example.baseball.service.GuessOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "숫자야구 스킬", description = "카카오 오픈빌더 스킬 엔드포인트")
@RestController
class SkillController(
    private val gameService: GameService,
) {
    /**
     * 카카오 오픈빌더 스킬 엔드포인트.
     * 카카오 5초 타임아웃 안에 끝나야 하므로 무거운 작업은 두지 않는다.
     */
    @Operation(
        summary = "발화 처리",
        description = "utterance 에 '시작'/'새게임' → 새 게임, 서로 다른 숫자 → 추측, '포기' → 정답 공개. 그 외엔 도움말.",
    )
    @PostMapping("/skill/play")
    fun play(@RequestBody request: SkillRequest): SkillResponse {
        val userId = request.userRequest.user.id
        val utterance = request.userRequest.utterance.trim()
        return SkillResponse.text(handle(userId, utterance))
    }

    /** utterance를 명령어/숫자로 분기. 사용자 입력 오류는 안내 메시지로 변환(500 대신 정상 응답). */
    private fun handle(userId: String, utterance: String): String =
        try {
            when {
                utterance in START_COMMANDS -> {
                    val game = gameService.startGame(userId)
                    "새 게임을 시작했습니다. ${game.digits}자리 숫자를 맞혀보세요. (예: 1234)"
                }

                utterance == GIVEUP_COMMAND -> {
                    val answer = gameService.giveUp(userId)
                    "게임을 포기했습니다. 정답은 $answer 였습니다. '시작'으로 다시 도전하세요."
                }

                utterance.isNotBlank() && utterance.all { it.isDigit() } -> {
                    formatGuess(gameService.guess(userId, utterance))
                }

                else -> helpMessage()
            }
        } catch (e: IllegalStateException) {
            // 진행 중 게임 없음 등
            e.message ?: "게임 상태를 확인할 수 없습니다."
        } catch (e: IllegalArgumentException) {
            // 자릿수/중복/숫자 외 문자 등 입력 규칙 위반
            e.message ?: "입력이 올바르지 않습니다."
        }

    private fun formatGuess(outcome: GuessOutcome): String {
        val r = outcome.result
        return when {
            r.isWin -> "정답입니다! ${outcome.tries}번 만에 맞혔습니다. '시작'으로 다시 플레이하세요."
            r.isOut -> "${outcome.tries}번째 시도: OUT (0S 0B)"
            else -> "${outcome.tries}번째 시도: ${r.strike}S ${r.ball}B"
        }
    }

    private fun helpMessage(): String =
        """
        숫자야구 게임입니다.
        - '시작' : 새 게임 시작
        - 서로 다른 숫자 입력 : 추측 (예: 1234)
        - '포기' : 정답 공개
        """.trimIndent()

    companion object {
        private val START_COMMANDS = setOf("시작", "새게임", "시작하기")
        private const val GIVEUP_COMMAND = "포기"
    }
}
