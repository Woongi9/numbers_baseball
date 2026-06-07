package com.example.baseball.service

import com.example.baseball.domain.game.Game
import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import io.mockk.CapturingSlot
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
import kotlin.test.assertTrue

class GameServiceTest {

    private val gameRepository = mockk<GameRepository>()
    private val sut = GameService(gameRepository)

    private val userId = "u1"

    /** save(game)가 들어온 엔티티를 그대로 반환하도록 설정 + 캡처 */
    private fun captureSave(): CapturingSlot<Game> {
        val slot = slot<Game>()
        every { gameRepository.save(capture(slot)) } answers { slot.captured }
        return slot
    }

    private fun noPlayingGame() {
        every { gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING) } returns null
    }

    private fun playingGame(answer: String): Game {
        val game = Game(userId = userId, answer = answer, digits = answer.length)
        every { gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING) } returns game
        return game
    }

    @Nested
    @DisplayName("startGame")
    inner class StartGame {

        @Test
        @DisplayName("진행중 게임이 없으면 서로 다른 숫자의 새 게임을 저장한다")
        fun createsNewGame() {
            noPlayingGame()
            val slot = captureSave()

            sut.startGame(userId, digits = 4)

            val saved = slot.captured
            assertEquals(userId, saved.userId)
            assertEquals(4, saved.digits)
            assertEquals(4, saved.answer.length)
            assertEquals(4, saved.answer.toSet().size) // 중복 없음
            assertTrue(saved.answer.all { it.isDigit() })
            assertEquals(GameStatus.PLAYING, saved.status)
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("진행중 게임이 있으면 기존 게임을 포기 처리한 뒤 새 게임을 만든다")
        fun abandonsExistingGame() {
            val existing = playingGame("1234")
            captureSave()

            sut.startGame(userId, digits = 4)

            assertEquals(GameStatus.GIVEUP, existing.status) // 기존 게임 종료됨
            verify(exactly = 1) { gameRepository.save(any()) }
        }

        @Test
        @DisplayName("허용 범위를 벗어난 자릿수는 예외")
        fun rejectsInvalidDigits() {
            assertThrows(IllegalArgumentException::class.java) {
                sut.startGame(userId, digits = 2)
            }
            assertThrows(IllegalArgumentException::class.java) {
                sut.startGame(userId, digits = 6)
            }
            // 검증 단계에서 막히므로 DB 접근이 없어야 한다
            verify(exactly = 0) { gameRepository.findFirstByUserIdAndStatus(any(), any()) }
            verify(exactly = 0) { gameRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("guess")
    inner class Guess {

        @Test
        @DisplayName("정답을 맞히면 WON으로 종료되고 finished=true")
        fun correctGuessWins() {
            val game = playingGame("5273")

            val outcome = sut.guess(userId, "5273")

            assertTrue(outcome.result.isWin)
            assertTrue(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.WON, game.status)
        }

        @Test
        @DisplayName("오답이면 PLAYING이 유지되고 시도 횟수만 증가한다")
        fun wrongGuessContinues() {
            val game = playingGame("5273")

            val outcome = sut.guess(userId, "1289") // 1S 0B

            assertFalse(outcome.result.isWin)
            assertFalse(outcome.finished)
            assertEquals(1, outcome.tries)
            assertEquals(GameStatus.PLAYING, game.status)
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            noPlayingGame()
            assertThrows(IllegalStateException::class.java) {
                sut.guess(userId, "1234")
            }
        }

        @Test
        @DisplayName("형식이 잘못된 입력은 예외이며 시도 횟수가 증가하지 않는다")
        fun invalidInputDoesNotConsumeTry() {
            val game = playingGame("5273")

            assertThrows(IllegalArgumentException::class.java) {
                sut.guess(userId, "5523") // 중복 숫자
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

            val answer = sut.giveUp(userId)

            assertEquals("5273", answer)
            assertEquals(GameStatus.GIVEUP, game.status)
        }

        @Test
        @DisplayName("진행중 게임이 없으면 예외")
        fun noGameThrows() {
            noPlayingGame()
            assertThrows(IllegalStateException::class.java) {
                sut.giveUp(userId)
            }
        }
    }
}
