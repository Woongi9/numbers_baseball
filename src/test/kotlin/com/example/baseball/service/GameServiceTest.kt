package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameDifficulty
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.confirmVerified
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

    private val botKey = "u1"

    /** save(game)가 들어온 엔티티를 그대로 반환하도록 설정 + 캡처 */
    private fun captureSave(): CapturingSlot<Game> {
        val slot = slot<Game>()
        every { gameRepository.save(capture(slot)) } answers { slot.captured }
        return slot
    }

    private fun noPlayingGame() {
        every { gameRepository.findFirstByBotKeyAndStatus(botKey, GameStatus.PLAYING) } returns null
    }

    private fun playingGame(answer: String): Game {
        val game = Game(botKey = botKey, answer = answer)
        every { gameRepository.findFirstByBotKeyAndStatus(botKey, GameStatus.PLAYING) } returns game
        return game
    }

    @Nested
    @DisplayName("startGame")
    inner class StartGame {

        /** 생성된 정답이 난이도 규칙(4자리 · 중복 없음 · 허용 기호만)을 지키는지 검증. */
        private fun assertValidAnswer(answer: String, difficulty: GameDifficulty) {
            assertEquals(4, answer.length, "정답은 4자리여야 한다")
            assertEquals(answer.length, answer.toSet().size, "정답에 중복 기호가 없어야 한다")
            val allowed = difficulty.symbols.toSet()
            assertTrue(answer.all { it in allowed }, "정답은 허용 기호만 사용해야 한다: $answer")
        }

        @Test
        @DisplayName("NORMAL: 0~9 숫자 4자리·score=100·PLAYING 게임을 저장하고 그대로 반환한다")
        fun createsNormalGame() {
            noPlayingGame()
            val slot = captureSave()

            val returned = sut.startGame(botKey, gameDifficulty = GameDifficulty.NORMAL)

            val saved = slot.captured
            assertEquals(returned, saved)                          // 저장한 엔티티를 그대로 반환
            assertEquals(botKey, saved.botKey)
            assertEquals(4, saved.answer.length)
            assertEquals(GameDifficulty.NORMAL, saved.gameDifficulty)
            assertEquals(100, saved.score)                         // 100 * 1.0
            assertEquals(0, saved.tries)
            assertEquals(GameStatus.PLAYING, saved.status)
            assertNull(saved.finishedAt)
            assertValidAnswer(saved.answer, GameDifficulty.NORMAL)
            assertTrue(saved.answer.all { it.isDigit() }, "NORMAL 정답은 전부 숫자")
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("시작 시 참가자(User/BotUser)를 register 로 미리 보장한다 (9-F 증상 2)")
        fun registersParticipantOnStart() {
            noPlayingGame()
            captureSave()

            sut.startGame(userId = "u1", botKey = "bot-1")

            // userId 를 appUserId·botUserKey 로 함께 전달해 행만 보장(점수 변동 없음).
            verify(exactly = 1) { userService.register("u1", "bot-1", "u1") }
        }

        @Test
        @DisplayName("botKey 가 없으면 register 에 null 을 넘겨 전역 User 만 보장한다")
        fun registersGlobalOnlyWhenNoBotKey() {
            noPlayingGame()
            captureSave()

            sut.startGame(userId = "u1")

            verify(exactly = 1) { userService.register("u1", null, "u1") }
        }

        @Test
        @DisplayName("HARD: 0~9+a~e 기호 4자리·score=200 게임을 저장한다")
        fun createsHardGame() {
            noPlayingGame()
            val slot = captureSave()

            sut.startGame(botKey, gameDifficulty = GameDifficulty.HARD)

            val saved = slot.captured
            assertEquals(GameDifficulty.HARD, saved.gameDifficulty)
            assertEquals(200, saved.score)                         // 100 * 2.0
            assertEquals(0, saved.tries)
            assertEquals(GameStatus.PLAYING, saved.status)
            assertValidAnswer(saved.answer, GameDifficulty.HARD)
            val allowed = (('0'..'9') + ('a'..'e')).toSet()        // 허용 집합 명시 검증
            assertTrue(saved.answer.all { it in allowed })
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("EASY: 0~5 숫자 4자리·score=50 게임을 저장한다")
        fun createsEasyGame() {
            noPlayingGame()
            val slot = captureSave()

            sut.startGame(botKey, gameDifficulty = GameDifficulty.EASY)

            val saved = slot.captured
            assertEquals(GameDifficulty.EASY, saved.gameDifficulty)
            assertEquals(50, saved.score)                          // 100 * 0.5
            assertEquals(GameStatus.PLAYING, saved.status)
            assertValidAnswer(saved.answer, GameDifficulty.EASY)
            val allowed = ('0'..'5').toSet()
            assertTrue(saved.answer.all { it in allowed })
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("진행중 게임이 있으면 기존 게임을 GIVEUP 처리한 뒤 새 게임을 만든다")
        fun abandonsExistingGame() {
            val existing = playingGame("1234")
            val slot = captureSave()

            sut.startGame(botKey, gameDifficulty = GameDifficulty.NORMAL)

            val saved = slot.captured
            assertEquals(GameStatus.GIVEUP, existing.status)       // 기존 게임 종료
            assertEquals(GameStatus.PLAYING, saved.status)         // 새 게임은 진행중
            assertNotEquals(existing, saved)                       // 서로 다른 엔티티
            verify(exactly = 1) { gameRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("guess")
    inner class Guess {

        @Test
        @DisplayName("정답을 맞히면 WON으로 종료되고 finished=true")
        fun correctGuessWins() {
            val game = playingGame("5273")

            val outcome = sut.guess(botKey, botKey, "5273")

            assertTrue(outcome.result.isWin)
            assertTrue(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.WON, game.status)
        }

        @Test
        @DisplayName("정답 시 gain 적립 위임 후 적립 점수로 상위% 조회 결과를 outcome 에 싣는다")
        fun winDelegatesAccrualAndPercentile() {
            val game = playingGame("5273") // NORMAL, 1번에 정답 → gain = 95
            // 적립 후 누적 점수를 1095 로 가정 → 그 점수로 percentileOf 가 조회되어야 한다.
            every { userService.accrue("u1", "bot-1", "u1", 95) } returns 1095
            every { userService.percentileOf(1095) } returns Percentile(rank = 5, total = 100, topPercent = 5)

            val outcome = sut.guess(userId = "u1", botKey = "bot-1", guess = "5273")

            assertEquals(95, outcome.gain)
            assertEquals(1095, outcome.totalScore)
            assertEquals(Percentile(5, 100, 5), outcome.percentile)
            verify(exactly = 1) { userService.accrue("u1", "bot-1", "u1", 95) }
            verify(exactly = 1) { userService.percentileOf(1095) }
            confirmVerified(userService)
        }

        @Test
        @DisplayName("오답이면 PLAYING 유지·시도수만 증가하고 적립은 호출되지 않는다(gain/totalScore=0)")
        fun wrongGuessContinues() {
            val game = playingGame("5273")

            val outcome = sut.guess(botKey, botKey, "1289") // 1S 0B

            assertFalse(outcome.result.isWin)
            assertFalse(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.PLAYING, game.status)
            assertEquals(0, outcome.gain)
            assertEquals(0, outcome.totalScore)
            verify(exactly = 0) { userService.accrue(any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            noPlayingGame()
            assertThrows(IllegalStateException::class.java) {
                sut.guess(botKey, botKey, "1234")
            }
        }

        @Test
        @DisplayName("형식이 잘못된 입력은 예외이며 시도 횟수가 증가하지 않는다")
        fun invalidInputDoesNotConsumeTry() {
            val game = playingGame("5273")

            assertThrows(IllegalArgumentException::class.java) {
                sut.guess(botKey, botKey, "5523") // 중복 숫자
            }
            assertEquals(0, game.tries) // 검증 실패 → recordTry 도달 X
            assertEquals(GameStatus.PLAYING, game.status)
        }
    }

    @Nested
    @DisplayName("giveUp")
    inner class GiveUp {

        @Test
        @DisplayName("포기하면 GIVEUP으로 종료되고 정답을 반환한다")
        fun giveUpReturnsAnswer() {
            val game = playingGame("5273")

            val answer = sut.giveUp(botKey)

            assertEquals("5273", answer)
            assertEquals(GameStatus.GIVEUP, game.status)
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            noPlayingGame()
            assertThrows(IllegalStateException::class.java) {
                sut.giveUp(botKey)
            }
        }
    }
}
