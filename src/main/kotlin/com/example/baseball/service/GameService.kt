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
