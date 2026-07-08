package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameDifficulty
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 추측 1회의 결과(컨트롤러가 응답 메시지를 만들 때 사용).
 * gain/totalScore 는 승리(win) 시에만 채워지고, 오답/오류 시에는 0 으로 둔다.
 */
data class GuessOutcome(
    val result: JudgeResult,
    val tries: Int,
    val finished: Boolean,
    /** 이번 승리로 획득한 점수(오답이면 0). */
    val gain: Int = 0,
    /** 적립 후 전역 누적 점수(오답이면 0). 응답의 "이전 → 현재" 표기에 사용. */
    val totalScore: Int = 0,
    /** 적립 후 전역 상위 백분위(오답이거나 표본 부족이면 null). */
    val percentile: Percentile? = null,
    /** 맞힌 정답(승리 시에만 채워짐, 오답이면 null). 승리 응답에 정답을 노출하는 데 쓴다. */
    val answer: String? = null,
)

@Service
class GameService(
    private val gameRepository: GameRepository,
    private val userService: UserService,
) {
    /**
     * 새 게임 시작. 진행 중인 게임이 있으면 포기 처리하여
     * "유저당 진행중 게임은 항상 1건" 불변식을 지킨다.
     *
     * 시작 시점에 참가자(User/BotUser) 행을 미리 보장한다(PLAN 9-F 증상 2):
     * 승리해야만 행이 생기던 문제를 막아, 점수 없는 참여자도 추적된다.
     *
     * @param userId 카카오 user.id. 게임 세션 키이자 전역 식별자(appUserId)·봇 내 식별자(botUserKey)로 함께 쓴다.
     * @param botKey 봇(채팅방) 식별자. null 이면 채팅방용 BotUser 등록은 생략하고 전역 User 만 보장한다.
     */
    @Transactional
    fun startGame(
        userId: String,
        botKey: String? = null,
        gameDifficulty: GameDifficulty = GameDifficulty.NORMAL,
    ): Game {
        userService.register(appUserId = userId, botKey = botKey, botUserKey = userId)
        gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)?.giveUp()
        val answer = generateAnswer(gameDifficulty)
        return gameRepository.save(
            Game(botKey = userId, answer = answer, gameDifficulty = gameDifficulty)
        )
    }

    /**
     * 추측 처리. 진행 중 게임을 찾아 판정하고 상태를 갱신한다.
     * 승리 시 같은 트랜잭션에서 score 를 산정·적립하여 게임 종료와 점수 적립을 원자적으로 커밋한다.
     *
     * @param userId  카카오 user.id. 게임 세션 키이자 전역 식별자(appUserId)·봇 내 식별자(botUserKey)로 함께 쓴다.
     * @param botKey  봇(채팅방) 식별자. null 이면 채팅방 랭킹용 BotUser 적립은 생략하고 전역 점수만 적립한다.
     * @throws IllegalStateException 진행 중인 게임이 없을 때
     * @throws IllegalArgumentException 입력이 규칙에 맞지 않을 때(BaseballJudge가 검증)
     */
    @Transactional
    fun guess(userId: String, botKey: String?, guess: String): GuessOutcome {
        val game = currentGame(userId)

        // 검증 실패 시 여기서 예외 → 시도 횟수는 증가하지 않는다(잘못된 입력은 차감 안 함).
        val result = BaseballJudge.judge(game.answer, guess)

        game.recordTry()

        if (!result.isWin) {
            return GuessOutcome(result = result, tries = game.tries, finished = false)
        }

        // 승리: 종료 → 시도수·난이도로 획득 점수 산정 → 전역/봇 누적 적립 → 같은 트랜잭션에서 상위% 조회.
        game.win()
        val gain = ScoreCalculator.gain(game.tries, game.gameDifficulty)
        val totalScore = userService.accrue(
            appUserId = userId, botKey = botKey, botUserKey = userId, gain = gain,
        )
        val percentile = userService.percentileOf(totalScore) // 적립 후 점수 기준
        return GuessOutcome(
            result = result, tries = game.tries, finished = true,
            gain = gain, totalScore = totalScore, percentile = percentile,
            answer = game.answer,
        )
    }

    /** 포기. 정답을 반환한다. */
    @Transactional
    fun giveUp(userId: String): String {
        val game = currentGame(userId)
        game.giveUp()
        return game.answer
    }

    private fun currentGame(userId: String): Game =
        gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)
            ?: throw IllegalStateException("진행 중인 게임이 없습니다. '시작'을 입력해 새 게임을 시작하세요.")

    private fun generateAnswer(gameDifficulty: GameDifficulty): String {
        return gameDifficulty.symbols.shuffled().take(DIGITS).joinToString("")
    }

    companion object {
        /** 게임 자릿수는 4자리로 고정. 난이도는 자릿수가 아니라 후보 기호 집합만 늘린다. */
        const val DIGITS = 4
    }
}
