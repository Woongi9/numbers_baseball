package com.example.baseball.domain.user

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DataJpaTest
@DisplayName("시즌 스냅샷 리포지토리")
class SeasonScoreRepositoryTest {

    @Autowired lateinit var seasonUserScoreRepository: SeasonUserScoreRepository
    @Autowired lateinit var seasonBotScoreRepository: SeasonBotScoreRepository

    @Test
    @DisplayName("season_user_scores 저장 후 existsByYearMonth/findByYearMonth 로 조회된다")
    fun savesAndQueriesUserSnapshot() {
        seasonUserScoreRepository.save(
            SeasonUserScore(yearMonth = "2026-06", userId = 1L, score = 120, topPercent = 3),
        )

        assertTrue(seasonUserScoreRepository.existsByYearMonth("2026-06"))
        assertFalse(seasonUserScoreRepository.existsByYearMonth("2026-07"))

        val rows = seasonUserScoreRepository.findByYearMonth("2026-06")
        assertEquals(1, rows.size)
        assertEquals(120, rows[0].score)
        assertEquals(3, rows[0].topPercent)
    }

    @Test
    @DisplayName("season_bot_scores 저장 후 findByYearMonth 로 조회된다")
    fun savesAndQueriesBotSnapshot() {
        seasonBotScoreRepository.save(
            SeasonBotScore(
                yearMonth = "2026-06", botKey = "bot", botUserKey = "u1",
                userId = 1L, score = 80, topPercent = 10,
            ),
        )

        val rows = seasonBotScoreRepository.findByYearMonth("2026-06")
        assertEquals(1, rows.size)
        assertEquals("u1", rows[0].botUserKey)
        assertEquals(80, rows[0].score)
        assertEquals(10, rows[0].topPercent)
    }
}
