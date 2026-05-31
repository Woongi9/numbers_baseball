package com.example.baseball.service

import com.example.baseball.domain.Game
import com.example.baseball.domain.GameRepository
import com.example.baseball.domain.GameStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 추측 1회의 결과(컨트롤러가 응답 메시지를 만들 때 사용). */
data class GuessOutcome(
    val result: JudgeResult,
    val tries: Int,
    val finished: Boolean,
)

@Service
class GameService(
    private val gameRepository: GameRepository,
) {
    /**
     * 새 게임 시작. 진행 중인 게임이 있으면 포기 처리하여
     * "유저당 진행중 게임은 항상 1건" 불변식을 지킨다.
     */
    @Transactional
    fun startGame(userId: String, digits: Int = DEFAULT_DIGITS): Game {
        require(digits in MIN_DIGITS..MAX_DIGITS) {
            "자릿수는 $MIN_DIGITS~$MAX_DIGITS 사이여야 합니다."
        }
        gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING)?.giveUp()

        val answer = generateAnswer(digits)
        return gameRepository.save(Game(userId = userId, answer = answer, digits = digits))
    }

    /**
     * 추측 처리. 진행 중 게임을 찾아 판정하고 상태를 갱신한다.
     * @throws IllegalStateException 진행 중인 게임이 없을 때
     * @throws IllegalArgumentException 입력이 규칙에 맞지 않을 때(BaseballJudge가 검증)
     */
    @Transactional
    fun guess(userId: String, guess: String): GuessOutcome {
        val game = currentGame(userId)

        // 검증 실패 시 여기서 예외 → 시도 횟수는 증가하지 않는다(잘못된 입력은 차감 안 함).
        val result = BaseballJudge.judge(game.answer, guess)

        game.recordTry()
        if (result.isWin) game.win()

        return GuessOutcome(result = result, tries = game.tries, finished = !game.isPlaying)
    }

    /** 포기. 정답을 반환한다. */
    @Transactional
    fun giveUp(userId: String): String {
        val game = currentGame(userId)
        game.giveUp()
        return game.answer
    }

    private fun currentGame(userId: String): Game =
        gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING)
            ?: throw IllegalStateException("진행 중인 게임이 없습니다. '시작'을 입력해 새 게임을 시작하세요.")

    /** 서로 다른 숫자 digits자리 정답 생성. (0~9 셔플 후 앞에서 digits개) */
    private fun generateAnswer(digits: Int): String =
        (0..9).shuffled().take(digits).joinToString("")

    companion object {
        const val DEFAULT_DIGITS = 4
        const val MIN_DIGITS = 3
        const val MAX_DIGITS = 5
    }
}
