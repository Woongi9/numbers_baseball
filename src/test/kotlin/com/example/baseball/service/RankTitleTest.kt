package com.example.baseball.service

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("RankTitle.of - 상위 백분위 → 칭호 매핑")
class RankTitleTest {

    @Nested
    @DisplayName("구간 매핑 (경계값 포함)")
    inner class Mapping {

        @Test
        @DisplayName("상위 1%~10% 이내면 TOP_10")
        fun top10() {
            assertEquals(RankTitle.TOP_10, RankTitle.of(1))
            assertEquals(RankTitle.TOP_10, RankTitle.of(10)) // 경계 포함
        }

        @Test
        @DisplayName("상위 11%~20% 이내면 TOP_20")
        fun top20() {
            assertEquals(RankTitle.TOP_20, RankTitle.of(11)) // 10% 바로 밖
            assertEquals(RankTitle.TOP_20, RankTitle.of(20)) // 경계 포함
        }

        @Test
        @DisplayName("상위 21%~30% 이내면 TOP_30")
        fun top30() {
            assertEquals(RankTitle.TOP_30, RankTitle.of(21)) // 20% 바로 밖
            assertEquals(RankTitle.TOP_30, RankTitle.of(30)) // 경계 포함
        }

        @Test
        @DisplayName("상위 30% 밖이면 칭호 없음(null)")
        fun noTitle() {
            assertNull(RankTitle.of(31)) // 30% 바로 밖
            assertNull(RankTitle.of(100)) // 꼴찌
        }
    }

    @Nested
    @DisplayName("불가능한 입력 방어")
    inner class Guard {

        @Test
        @DisplayName("topPercent 가 1 미만이면 예외")
        fun rejectBelowOne() {
            assertThrows(IllegalArgumentException::class.java) {
                RankTitle.of(0)
            }
        }
    }
}
