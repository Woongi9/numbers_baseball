package com.example.baseball.service

import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.User
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("RankingService - 봇별 랭킹 매핑(읽기)")
class RankingServiceTest {

    private val botUserRepository = mockk<BotUserRepository>()
    private val sut = RankingService(botUserRepository)

    private val botKey = "bot-A"

    private fun botUser(botUserKey: String, score: Int): BotUser =
        BotUser(
            user = User(appUserId = "app-$botUserKey"),
            botUserKey = botUserKey,
            botKey = botKey,
            score = score,
        )

    private fun givenRanking(vararg rows: BotUser) {
        every { botUserRepository.findTop10ByBotKeyOrderByScoreDesc(botKey) } returns rows.toList()
    }

    @Test
    @DisplayName("리포지토리 순서를 1위부터 순번 매겨 점수와 함께 매핑한다")
    fun mapsRankAndScore() {
        givenRanking(botUser("longkey1", 300), botUser("longkey2", 200))

        val ranking = sut.getBotRanking(botKey)

        assertEquals(listOf(1, 2), ranking.map { it.rank })
        assertEquals(listOf(300, 200), ranking.map { it.score })
    }

    @Test
    @DisplayName("긴 사용자 키는 앞 4글자 + … 로 마스킹한다")
    fun masksLongKey() {
        givenRanking(botUser("abcdef123", 100))

        val entry = sut.getBotRanking(botKey).first()

        assertEquals("abcd…", entry.label)
    }

    @Test
    @DisplayName("4글자 이하 키는 그대로 노출한다")
    fun keepsShortKey() {
        givenRanking(botUser("u1", 100))

        assertEquals("u1", sut.getBotRanking(botKey).first().label)
    }

    @Test
    @DisplayName("점수가 없으면 빈 목록")
    fun emptyRanking() {
        givenRanking()
        assertEquals(emptyList(), sut.getBotRanking(botKey))
    }
}
