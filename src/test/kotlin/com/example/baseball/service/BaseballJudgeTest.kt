package com.example.baseball.service

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseballJudgeTest {

    @Nested
    @DisplayName("판정 로직")
    inner class Judge {

        @Test
        @DisplayName("자리와 숫자가 모두 맞으면 스트라이크")
        fun strike() {
            // 5273 vs 5283 -> 5,2,3 자리 일치(3S), 8은 정답에 없음(0B)
            val result = BaseballJudge.judge("5273", "5283")
            assertEquals(JudgeResult(strike = 3, ball = 0, isWin = false), result)
        }

        @Test
        @DisplayName("숫자는 맞지만 자리가 모두 다르면 볼")
        fun ball() {
            // 5273 vs 2735 -> 같은 자리 없음, 네 숫자 모두 포함(0S 4B)
            val result = BaseballJudge.judge("5273", "2735")
            assertEquals(JudgeResult(strike = 0, ball = 4, isWin = false), result)
        }

        @Test
        @DisplayName("스트라이크로 잡힌 숫자는 볼에서 제외된다")
        fun strikeNotCountedAsBall() {
            // 5273 vs 1289 -> 2만 자리 일치(1S), 나머지 1,8,9 미포함(0B)
            val result = BaseballJudge.judge("5273", "1289")
            assertEquals(JudgeResult(strike = 1, ball = 0, isWin = false), result)
        }

        @Test
        @DisplayName("모두 틀리면 아웃(0S 0B)")
        fun out() {
            val result = BaseballJudge.judge("5273", "1489")
            assertEquals(JudgeResult(strike = 0, ball = 0, isWin = false), result)
            assertTrue(result.isOut)
        }

        @Test
        @DisplayName("완전히 일치하면 승리(4S)")
        fun homeRun() {
            val result = BaseballJudge.judge("5273", "5273")
            assertEquals(JudgeResult(strike = 4, ball = 0, isWin = true), result)
            assertTrue(result.isWin)
            assertFalse(result.isOut)
        }

        @Test
        @DisplayName("3자리 게임도 동일하게 판정한다")
        fun threeDigits() {
            val result = BaseballJudge.judge("123", "321")
            assertEquals(JudgeResult(strike = 1, ball = 2, isWin = false), result) // 2만 자리 일치
            assertFalse(result.isWin)
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        @DisplayName("자릿수가 정답과 다르면 예외")
        fun wrongLength() {
            assertThrows(IllegalArgumentException::class.java) {
                BaseballJudge.judge("5273", "527")
            }
        }

        @Test
        @DisplayName("숫자가 아닌 문자가 있으면 예외")
        fun notNumeric() {
            assertThrows(IllegalArgumentException::class.java) {
                BaseballJudge.judge("5273", "52a3")
            }
        }

        @Test
        @DisplayName("중복된 숫자가 있으면 예외")
        fun hasDuplicate() {
            assertThrows(IllegalArgumentException::class.java) {
                BaseballJudge.judge("5273", "5523")
            }
        }

        @Test
        @DisplayName("빈 입력이면 예외")
        fun blank() {
            assertThrows(IllegalArgumentException::class.java) {
                BaseballJudge.judge("5273", "")
            }
        }
    }
}
