package com.example.baseball.service

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("PercentileCalculator.of - 상위 백분위 계산")
class PercentileCalculatorTest {

    @Nested
    @DisplayName("정상 계산 (표본 충분)")
    inner class Normal {

        @Test
        @DisplayName("1등(나보다 높은 사람 0명)이면 상위 1%")
        fun firstPlace() {
            // total=100, higher=0 → rank=1, ceil(1*100/100)=1
            val p = PercentileCalculator.of(higher = 0, total = 100)!!
            assertEquals(1, p.rank)
            assertEquals(100, p.total)
            assertEquals(1, p.topPercent)
        }

        @Test
        @DisplayName("나보다 높은 사람 4명이면 5위·상위 5%")
        fun midRank() {
            // total=100, higher=4 → rank=5, ceil(5)=5
            val p = PercentileCalculator.of(higher = 4, total = 100)!!
            assertEquals(5, p.rank)
            assertEquals(5, p.topPercent)
        }

        @Test
        @DisplayName("꼴찌(나만 맨 아래)면 상위 100%")
        fun lastPlace() {
            // total=100, higher=99 → rank=100, ceil(100)=100
            val p = PercentileCalculator.of(higher = 99, total = 100)!!
            assertEquals(100, p.rank)
            assertEquals(100, p.topPercent)
        }

        @Test
        @DisplayName("동점자는 strictly-greater 라 같은 등수로 묶인다(공동순위)")
        fun ties() {
            // 최고점 동점 유저들: 서로의 점수가 '더 높지' 않으므로 각자 higher=0 → 모두 1위.
            val p = PercentileCalculator.of(higher = 0, total = 10)!!
            assertEquals(1, p.rank)
            assertEquals(1, p.topPercent)
        }

        @Test
        @DisplayName("백분위는 올림(ceil) 처리한다")
        fun ceiling() {
            // total=7, higher=1 → rank=2, ceil(2*100/7)=ceil(28.57)=29
            val p = PercentileCalculator.of(higher = 1, total = 7)!!
            assertEquals(2, p.rank)
            assertEquals(29, p.topPercent)
        }
    }

    @Nested
    @DisplayName("표본 부족 분기")
    inner class TooFewSamples {

        @Test
        @DisplayName("total 이 MIN_SAMPLE 미만이면 null")
        fun belowThresholdIsNull() {
            assertNull(PercentileCalculator.of(higher = 0, total = PercentileCalculator.MIN_SAMPLE - 1))
        }

        @Test
        @DisplayName("혼자(total=1)면 null (항상 100%가 되어 무의미)")
        fun aloneIsNull() {
            assertNull(PercentileCalculator.of(higher = 0, total = 1))
        }

        @Test
        @DisplayName("경계: total == MIN_SAMPLE 이면 노출된다")
        fun atThresholdIsShown() {
            val p = PercentileCalculator.of(higher = 0, total = PercentileCalculator.MIN_SAMPLE)
            assertEquals(1, p!!.rank)
        }
    }

    @Nested
    @DisplayName("잘못된 입력")
    inner class InvalidInput {

        @Test
        @DisplayName("higher 가 total 이상이면 예외(불가능한 입력)")
        fun higherNotLessThanTotal() {
            assertThrows(IllegalArgumentException::class.java) {
                PercentileCalculator.of(higher = 10, total = 10)
            }
        }

        @Test
        @DisplayName("음수 입력은 예외")
        fun negativeThrows() {
            assertThrows(IllegalArgumentException::class.java) {
                PercentileCalculator.of(higher = -1, total = 10)
            }
        }
    }
}
