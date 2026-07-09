package com.example.baseball.domain.user

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals

@DataJpaTest
@DisplayName("BotUserRepository - 봇별 랭킹 조회")
class BotUserRepositoryTest @Autowired constructor(
    private val botUserRepository: BotUserRepository,
    private val userRepository: UserRepository,
) {
    /** User(FK, score=score) 저장 후 그 유저의 BotUser(botKey, botUserKey) 를 저장한다. */
    private fun seed(botKey: String, botUserKey: String, score: Int) {
        val user = userRepository.save(User(appUserId = "app-$botUserKey").apply { this.score = score })
        botUserRepository.save(
            BotUser(user = user, botUserKey = botUserKey, botKey = botKey)
        )
    }

    @Test
    @DisplayName("같은 botKey 안에서 user.score 내림차순으로 정렬된다")
    fun ordersByScoreDesc() {
        seed("bot-A", "u1", 100)
        seed("bot-A", "u2", 300)
        seed("bot-A", "u3", 200)

        val ranking = botUserRepository.findTop10ByBotKeyOrderByUser_ScoreDesc("bot-A")

        assertEquals(listOf("u2", "u3", "u1"), ranking.map { it.botUserKey })
        assertEquals(listOf(300, 200, 100), ranking.map { it.user.score })
    }

    @Test
    @DisplayName("다른 botKey 유저는 결과에 섞이지 않는다")
    fun filtersByBotKey() {
        seed("bot-A", "a1", 100)
        seed("bot-B", "b1", 999) // 점수는 더 높지만 다른 봇

        val ranking = botUserRepository.findTop10ByBotKeyOrderByUser_ScoreDesc("bot-A")

        assertEquals(1, ranking.size)
        assertEquals("a1", ranking.first().botUserKey)
    }

    @Test
    @DisplayName("11명 이상이어도 TOP 10 까지만 반환한다")
    fun limitsToTen() {
        (1..15).forEach { seed("bot-A", "u$it", it * 10) } // 10~150점

        val ranking = botUserRepository.findTop10ByBotKeyOrderByUser_ScoreDesc("bot-A")

        assertEquals(10, ranking.size)
        assertEquals(150, ranking.first().user.score) // 최고점
        assertEquals(60, ranking.last().user.score)   // 10등 = 60점(150,140,...,60)
    }

    @Test
    @DisplayName("해당 봇에 점수가 없으면 빈 목록")
    fun emptyWhenNoData() {
        seed("bot-A", "a1", 100)
        assertEquals(emptyList(), botUserRepository.findTop10ByBotKeyOrderByUser_ScoreDesc("bot-empty"))
    }
}
