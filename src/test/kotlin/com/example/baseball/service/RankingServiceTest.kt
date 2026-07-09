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
            user = User(appUserId = "app-$botUserKey").apply { this.score = score },
            botUserKey = botUserKey,
            botKey = botKey,
        )

    private fun givenRanking(vararg rows: BotUser, total: Int = rows.size) {
        every { botUserRepository.findTop10ByBotKeyOrderByUser_ScoreDesc(botKey) } returns rows.toList()
        every { botUserRepository.countByBotKey(botKey) } returns total.toLong()
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
    @DisplayName("botUserKey 를 멘션 id 용으로 원본 그대로 노출한다")
    fun exposesRawBotUserKey() {
        givenRanking(botUser("abcdef123", 100))

        val entry = sut.getBotRanking(botKey).first()

        assertEquals("abcdef123", entry.botUserKey)
    }

    @Test
    @DisplayName("점수가 없으면 빈 목록")
    fun emptyRanking() {
        givenRanking()
        assertEquals(emptyList(), sut.getBotRanking(botKey))
    }

    @Test
    @DisplayName("표본이 충분하면 상위 10/20/30% 구간에 RankTitle 뱃지를 부여한다")
    fun assignsRankTitleBadge() {
        // 전체 10명(모두 서로 다른 점수). topPercent = ceil(rank*100/10).
        // rank1=10%→TOP_10, rank2=20%→TOP_20, rank3=30%→TOP_30, rank4=40%→null
        givenRanking(
            botUser("u1", 100), botUser("u2", 90), botUser("u3", 80), botUser("u4", 70),
            botUser("u5", 60), botUser("u6", 50), botUser("u7", 40), botUser("u8", 30),
            botUser("u9", 20), botUser("u10", 10),
            total = 10,
        )

        val ranking = sut.getBotRanking(botKey)

        assertEquals(RankTitle.TOP_10, ranking[0].title)
        assertEquals(RankTitle.TOP_20, ranking[1].title)
        assertEquals(RankTitle.TOP_30, ranking[2].title)
        assertEquals(null, ranking[3].title)
    }

    @Test
    @DisplayName("표본이 MIN_SAMPLE 미만이면 뱃지를 부여하지 않는다(title=null)")
    fun noBadgeBelowMinSample() {
        givenRanking(botUser("u1", 300), botUser("u2", 200), total = 2)

        assertEquals(null, sut.getBotRanking(botKey).first().title)
    }
}
