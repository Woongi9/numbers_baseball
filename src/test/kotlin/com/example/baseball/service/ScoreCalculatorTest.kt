package com.example.baseball.service

import com.example.baseball.domain.game.GameDifficulty
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreCalculatorTest {

    @Nested
    @DisplayName("기본 산정(NORMAL ×1.0)")
    inner class Normal {

        @Test
        @DisplayName("1번에 맞히면 최대 점수(95)")
        fun bestWhenOneTry() {
            // max(20, 100 - 1*5) = 95
            assertEquals(95, ScoreCalculator.gain(1, GameDifficulty.NORMAL))
        }

        @Test
        @DisplayName("시도가 많아질수록 점수는 단조 감소한다")
        fun monotonicallyDecreasing() {
            // 바닥에 닿기 전(1~16)까지는 엄격히 감소해야 한다.
            for (t in 2..16) {
                val prev = ScoreCalculator.gain(t - 1, GameDifficulty.NORMAL)
                val curr = ScoreCalculator.gain(t, GameDifficulty.NORMAL)
                assertTrue(curr < prev, "tries=$t 에서 감소하지 않음: $prev -> $curr")
            }
        }

        @Test
        @DisplayName("많이 틀려도 MIN_GAIN(20) 아래로는 안 내려간다")
        fun flooredAtMinGain() {
            assertEquals(20, ScoreCalculator.gain(16, GameDifficulty.NORMAL)) // 100-80=20, 경계
            assertEquals(20, ScoreCalculator.gain(17, GameDifficulty.NORMAL)) // 15였지만 바닥
            assertEquals(20, ScoreCalculator.gain(100, GameDifficulty.NORMAL))
        }

        @Test
        @DisplayName("경계값: 바닥 직전 tries=15는 25, tries=16은 20")
        fun boundaryAroundFloor() {
            assertEquals(25, ScoreCalculator.gain(15, GameDifficulty.NORMAL)) // 100-75=25
            assertEquals(20, ScoreCalculator.gain(16, GameDifficulty.NORMAL)) // 100-80=20
        }
    }

    @Nested
    @DisplayName("난이도 배수")
    inner class Difficulty {

        @Test
        @DisplayName("HARD는 2배(190), EASY는 0.5배(47, 소수점 버림)")
        fun multiplierApplied() {
            assertEquals(190, ScoreCalculator.gain(1, GameDifficulty.HARD)) // 95 * 2.0
            assertEquals(47, ScoreCalculator.gain(1, GameDifficulty.EASY))  // 95 * 0.5 = 47.5 -> 47
        }

        @Test
        @DisplayName("바닥값에도 배수가 적용된다(HARD 40 / EASY 10)")
        fun multiplierAppliedAtFloor() {
            assertEquals(40, ScoreCalculator.gain(100, GameDifficulty.HARD)) // 20 * 2.0
            assertEquals(10, ScoreCalculator.gain(100, GameDifficulty.EASY)) // 20 * 0.5
        }

        @Test
        @DisplayName("기본 난이도는 NORMAL이다")
        fun defaultIsNormal() {
            assertEquals(
                ScoreCalculator.gain(7, GameDifficulty.NORMAL),
                ScoreCalculator.gain(7),
            )
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        @DisplayName("tries가 0이면 예외(승리는 최소 1회 시도 필요)")
        fun zeroTries() {
            assertThrows(IllegalArgumentException::class.java) {
                ScoreCalculator.gain(0, GameDifficulty.NORMAL)
            }
        }

        @Test
        @DisplayName("tries가 음수면 예외")
        fun negativeTries() {
            assertThrows(IllegalArgumentException::class.java) {
                ScoreCalculator.gain(-3, GameDifficulty.NORMAL)
            }
        }
    }
}
