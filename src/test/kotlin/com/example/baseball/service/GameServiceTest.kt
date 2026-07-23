package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameDifficulty
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import com.example.baseball.dto.ChatIdentity
import io.mockk.CapturingSlot
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameServiceTest {

    private val gameRepository = mockk<GameRepository>()
    // 적립은 UserService 책임이므로 여기서는 호출 위임만 검증한다(상호작용 테스트).
    private val userService = mockk<UserService>(relaxed = true)
    private val sut = GameService(gameRepository, userService)

    private val identity = ChatIdentity(appUserId = "app-1", botUserKey = "buk-1", botKey = "bot-1")

    private fun captureSave(): CapturingSlot<Game> {
        val slot = slot<Game>()
        every { gameRepository.save(capture(slot)) } answers { slot.captured }
        return slot
    }

    /** 방의 진행중 게임을 세팅한다. 마지막 인자가 "최신" 게임으로 취급된다. */
    private fun playingGames(vararg games: Game) {
        every {
            gameRepository.findAllByBotKeyAndStatus(identity.botKey, GameStatus.PLAYING)
        } returns games.toList()
        every {
            gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(identity.botKey, GameStatus.PLAYING)
        } returns games.lastOrNull()
    }

    private fun game(answer: String) = Game(botKey = identity.botKey, answer = answer)

    @Nested
    @DisplayName("startGame")
    inner class StartGame {

        private fun assertValidAnswer(answer: String, difficulty: GameDifficulty) {
            assertEquals(4, answer.length, "정답은 4자리여야 한다")
            assertEquals(answer.length, answer.toSet().size, "정답에 중복 기호가 없어야 한다")
            val allowed = difficulty.symbols.toSet()
            assertTrue(answer.all { it in allowed }, "정답은 허용 기호만 사용해야 한다: $answer")
        }

        @Test
        @DisplayName("NORMAL: 0~9 숫자 4자리·score=100·PLAYING 게임을 방 키로 저장한다")
        fun createsNormalGame() {
            playingGames()
            val slot = captureSave()

            val outcome = sut.startGame(identity, gameDifficulty = GameDifficulty.NORMAL)

            val saved = slot.captured
            assertEquals(outcome.game, saved)
            assertNull(outcome.replacedAnswer)
            assertEquals("bot-1", saved.botKey)
            assertEquals(GameDifficulty.NORMAL, saved.gameDifficulty)
            assertEquals(100, saved.score)
            assertEquals(0, saved.tries)
            assertEquals(GameStatus.PLAYING, saved.status)
            assertNull(saved.finishedAt)
            assertValidAnswer(saved.answer, GameDifficulty.NORMAL)
            assertTrue(saved.answer.all { it.isDigit() }, "NORMAL 정답은 전부 숫자")
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("시작 시 참가자(User/BotUser)를 register 로 미리 보장한다")
        fun registersParticipantOnStart() {
            playingGames()
            captureSave()

            sut.startGame(identity)

            verify(exactly = 1) { userService.register(identity) }
        }

        @Test
        @DisplayName("HARD: 0~9+a~e 기호 4자리·score=200 게임을 저장한다")
        fun createsHardGame() {
            playingGames()
            val slot = captureSave()

            sut.startGame(identity, gameDifficulty = GameDifficulty.HARD)

            val saved = slot.captured
            assertEquals(GameDifficulty.HARD, saved.gameDifficulty)
            assertEquals(200, saved.score)
            assertValidAnswer(saved.answer, GameDifficulty.HARD)
            val allowed = (('0'..'9') + ('a'..'e')).toSet()
            assertTrue(saved.answer.all { it in allowed })
        }

        @Test
        @DisplayName("EASY: 0~5 숫자 4자리·score=50 게임을 저장한다")
        fun createsEasyGame() {
            playingGames()
            val slot = captureSave()

            sut.startGame(identity, gameDifficulty = GameDifficulty.EASY)

            val saved = slot.captured
            assertEquals(GameDifficulty.EASY, saved.gameDifficulty)
            assertEquals(50, saved.score)
            assertValidAnswer(saved.answer, GameDifficulty.EASY)
            val allowed = ('0'..'5').toSet()
            assertTrue(saved.answer.all { it in allowed })
        }

        @Test
        @DisplayName("방에 진행중 게임이 있으면 GIVEUP 처리하고 그 정답을 replacedAnswer 로 알린다")
        fun abandonsExistingGame() {
            val existing = game("1234")
            playingGames(existing)
            val slot = captureSave()

            val outcome = sut.startGame(identity)

            assertEquals(GameStatus.GIVEUP, existing.status)
            assertEquals("1234", outcome.replacedAnswer)
            assertEquals(GameStatus.PLAYING, slot.captured.status)
            assertNotEquals(existing, slot.captured)
        }

        @Test
        @DisplayName("동시 시작으로 유령 게임이 2건 남아 있으면 전부 GIVEUP 처리한다")
        fun abandonsAllGhostGames() {
            val older = game("1234")
            val newer = game("5678")
            playingGames(older, newer)
            captureSave()

            sut.startGame(identity)

            assertEquals(GameStatus.GIVEUP, older.status)
            assertEquals(GameStatus.GIVEUP, newer.status)
        }
    }

    @Nested
    @DisplayName("guess")
    inner class Guess {

        @Test
        @DisplayName("정답을 맞히면 WON으로 종료되고 finished=true")
        fun correctGuessWins() {
            val g = game("5273")
            playingGames(g)

            val outcome = sut.guess(identity, "5273")

            assertTrue(outcome.result.isWin)
            assertTrue(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.WON, g.status)
        }

        @Test
        @DisplayName("유령 게임이 2건이면 최신 1건으로 판정한다")
        fun usesLatestGame() {
            val older = game("1234")
            val newer = game("5678")
            playingGames(older, newer)

            val outcome = sut.guess(identity, "5678")

            assertTrue(outcome.result.isWin)
            assertEquals(GameStatus.WON, newer.status)
            assertEquals(GameStatus.PLAYING, older.status)
        }

        @Test
        @DisplayName("정답 시 gain 적립 위임 후 적립 점수로 상위% 조회 결과를 outcome 에 싣는다")
        fun winDelegatesAccrualAndPercentile() {
            playingGames(game("5273")) // NORMAL, 1번에 정답 → gain = 95
            every { userService.accrue(identity, 95) } returns 1095
            every { userService.percentileOf(1095) } returns Percentile(rank = 5, total = 100, topPercent = 5)

            val outcome = sut.guess(identity, "5273")

            assertEquals(95, outcome.gain)
            assertEquals(1095, outcome.totalScore)
            assertEquals(Percentile(5, 100, 5), outcome.percentile)
            verify(exactly = 1) { userService.accrue(identity, 95) }
            verify(exactly = 1) { userService.percentileOf(1095) }
            confirmVerified(userService)
        }

        @Test
        @DisplayName("오답이면 PLAYING 유지·시도수만 증가하고 적립은 호출되지 않는다")
        fun wrongGuessContinues() {
            val g = game("5273")
            playingGames(g)

            val outcome = sut.guess(identity, "1289") // 1S 0B

            assertFalse(outcome.result.isWin)
            assertFalse(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.PLAYING, g.status)
            assertEquals(0, outcome.gain)
            assertEquals(0, outcome.totalScore)
            verify(exactly = 0) { userService.accrue(any(), any()) }
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            playingGames()

            assertThrows(IllegalStateException::class.java) { sut.guess(identity, "1234") }
        }

        @Test
        @DisplayName("형식이 잘못된 입력은 예외이며 시도 횟수가 증가하지 않는다")
        fun invalidInputDoesNotConsumeTry() {
            val g = game("5273")
            playingGames(g)

            assertThrows(IllegalArgumentException::class.java) { sut.guess(identity, "5523") }
            assertEquals(0, g.tries)
            assertEquals(GameStatus.PLAYING, g.status)
        }
    }

    @Nested
    @DisplayName("giveUp")
    inner class GiveUp {

        @Test
        @DisplayName("포기하면 GIVEUP으로 종료되고 정답을 반환한다")
        fun giveUpReturnsAnswer() {
            val g = game("5273")
            playingGames(g)

            assertEquals("5273", sut.giveUp(identity))
            assertEquals(GameStatus.GIVEUP, g.status)
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            playingGames()

            assertThrows(IllegalStateException::class.java) { sut.giveUp(identity) }
        }
    }
}
