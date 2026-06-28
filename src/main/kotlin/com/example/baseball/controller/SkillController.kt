package com.example.baseball.controller

import com.example.baseball.dto.SkillRequest
import com.example.baseball.dto.SkillResponse
import com.example.baseball.service.GameService
import com.example.baseball.service.GuessOutcome
import com.example.baseball.service.RankEntry
import com.example.baseball.service.RankingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "숫자야구 스킬", description = "카카오 오픈빌더 스킬 엔드포인트")
@RestController
class SkillController(
    private val gameService: GameService,
    private val rankingService: RankingService,
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
        val botKey = request.bot?.id
        val utterance = request.userRequest.utterance.trim()
        return SkillResponse.text(handle(userId, botKey, utterance))
    }

    /** utterance를 명령어/숫자로 분기. 사용자 입력 오류는 안내 메시지로 변환(500 대신 정상 응답). */
    private fun handle(userId: String, botKey: String?, utterance: String): String =
        try {
            when {
                utterance in START_COMMANDS -> {
                    gameService.startGame(userId)
                    "새 게임을 시작했습니다. ${GameService.DIGITS}자리 숫자를 맞혀보세요. (예: 1234)"
                }

                utterance == GIVEUP_COMMAND -> {
                    val answer = gameService.giveUp(userId)
                    "게임을 포기했습니다. 정답은 $answer 였습니다. '시작'으로 다시 도전하세요."
                }

                utterance in RANKING_COMMANDS -> formatRanking(botKey)

                utterance.isNotBlank() && utterance.all { it.isDigit() } -> {
                    formatGuess(gameService.guess(userId, botKey, utterance))
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
            r.isWin -> {
                val before = outcome.totalScore - outcome.gain   // 적립 전 누적 점수
                buildString {
                    appendLine("정답입니다! ${outcome.tries}번 만에 맞혔습니다.")
                    appendLine("+${outcome.gain}점 ($before → ${outcome.totalScore})")
                    // 표본이 충분할 때만(percentile != null) 상위% 줄을 노출한다(적으면 무의미해 생략).
                    outcome.percentile?.let {
                        appendLine("🏅 이번 시즌 상위 ${it.topPercent}% (${it.rank}위 / ${it.total}명)")
                    }
                    append("'시작'으로 다시 플레이하세요.")
                }
            }
            r.isOut -> "${outcome.tries}번째 시도: OUT (0S 0B)"
            else -> "${outcome.tries}번째 시도: ${r.strike}S ${r.ball}B"
        }
    }

    /** 봇(채팅방) 랭킹 TOP 10 텍스트. botKey 없음·빈 랭킹은 안내 메시지로 변환. */
    private fun formatRanking(botKey: String?): String {
        if (botKey == null) return "채팅방 정보를 확인할 수 없어 랭킹을 보여줄 수 없습니다."

        val ranking = rankingService.getBotRanking(botKey)
        if (ranking.isEmpty()) {
            return "아직 랭킹에 등록된 점수가 없습니다. 게임에서 정답을 맞히면 점수가 쌓여요."
        }
        return buildString {
            appendLine("🏆 이 채팅방 랭킹 TOP ${ranking.size}")
            ranking.forEach { append(formatRankLine(it)) }
        }.trimEnd()
    }

    private fun formatRankLine(e: RankEntry): String =
        "${e.rank}위  ${e.label}  ${e.score}점\n"

    private fun helpMessage(): String =
        """
        숫자야구 게임입니다.
        - '시작' : 새 게임 시작
        - 서로 다른 숫자 입력 : 추측 (예: 1234)
        - '포기' : 정답 공개
        - '랭킹' : 이 채팅방 점수 순위
        """.trimIndent()

    companion object {
        private val START_COMMANDS = setOf("시작", "새게임", "시작하기")
        private const val GIVEUP_COMMAND = "포기"
        private val RANKING_COMMANDS = setOf("랭킹", "봇랭킹", "순위")
    }
}
