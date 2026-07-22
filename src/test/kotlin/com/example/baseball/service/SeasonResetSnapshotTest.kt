package com.example.baseball.service

import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.SeasonBotScoreRepository
import com.example.baseball.domain.user.SeasonUserScoreRepository
import com.example.baseball.domain.user.User
import com.example.baseball.domain.user.UserRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@DisplayName("SeasonReset - 리셋 전 스냅샷 보존")
class SeasonResetSnapshotTest @Autowired constructor(
    private val seasonReset: SeasonReset,
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
    private val seasonUserScoreRepository: SeasonUserScoreRepository,
    private val seasonBotScoreRepository: SeasonBotScoreRepository,
) {
    private val ym: String = YearMonth.now(ZoneId.of("Asia/Seoul")).minusMonths(1).toString()

    private fun cleanup() {
        seasonUserScoreRepository.deleteAll()
        seasonBotScoreRepository.deleteAll()
        botUserRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    @DisplayName("score>0 유저/봇유저를 스냅샷하고 점수를 0으로 리셋한다")
    fun snapshotsThenResets() {
        cleanup()
        // 코호트 5명(MIN_SAMPLE) 충족: 점수 100/40/30/20/0
        val u1 = userRepository.save(User(appUserId = "a").apply { score = 100 })
        val u2 = userRepository.save(User(appUserId = "b").apply { score = 40 })
        val u3 = userRepository.save(User(appUserId = "c").apply { score = 30 })
        val u4 = userRepository.save(User(appUserId = "d").apply { score = 20 })
        userRepository.save(User(appUserId = "e").apply { score = 0 }) // 0점: 스냅샷 제외
        botUserRepository.save(BotUser(user = u1, botUserKey = "k1", botKey = "bot", score = 999))

        seasonReset.reset()

        // 전역 스냅샷: 0점 제외 → 4행, year_month = 직전 달
        val userRows = seasonUserScoreRepository.findByYearMonth(ym)
        assertEquals(4, userRows.size)
        val topRow = userRows.first { it.userId == u1.id }
        assertEquals(100, topRow.score)
        // 1등(higher=0, total=5) → ceil(1*100/5)=20
        assertEquals(20, topRow.topPercent)

        // 채팅방 스냅샷: score = user.score(100), BotUser.score(999) 아님
        val botRows = seasonBotScoreRepository.findByYearMonth(ym)
        assertEquals(1, botRows.size)
        assertEquals("k1", botRows[0].botUserKey)
        assertEquals(100, botRows[0].score)

        // 리셋: 모든 점수 0
        assertEquals(0, userRepository.findAll().sumOf { it.score })
        assertEquals(0, botUserRepository.findAll().sumOf { it.score })
        cleanup()
    }

    @Test
    @DisplayName("코호트가 MIN_SAMPLE 미만이면 top_percent 는 null 이다")
    fun nullPercentileBelowMinSample() {
        cleanup()
        userRepository.save(User(appUserId = "solo").apply { score = 10 })

        seasonReset.reset()

        val rows = seasonUserScoreRepository.findByYearMonth(ym)
        assertEquals(1, rows.size)
        assertNull(rows[0].topPercent)
        cleanup()
    }

    @Test
    @DisplayName("같은 달 재실행은 멱등 — 두 번째 호출은 스킵되어 중복이 없다")
    fun idempotentWithinSameMonth() {
        cleanup()
        userRepository.save(User(appUserId = "a").apply { score = 50 })

        seasonReset.reset()
        // 두 번째 호출: 점수는 이미 0이지만, 가드가 없으면 uniq 충돌/오염 위험
        seasonReset.reset()

        assertEquals(1, seasonUserScoreRepository.findByYearMonth(ym).size)
        assertTrue(seasonUserScoreRepository.existsByYearMonth(ym))
        cleanup()
    }

    // 한 유저가 여러 방(botKey)에 참가할 때, botUserKey 가 방 간 같은/다른 두 경우 모두 커버한다.
    // 카카오 botUserKey 는 '챗봇 기준' 키라 방 간 유일성이 보장되지 않으므로 두 경우가 다 발생할 수 있고,
    // 스냅샷 유니크 (year_month, bot_key, bot_user_key) 는 둘 다 롤백 없이 방별 1행으로 담아야 한다.

    @Test
    @DisplayName("여러 방에서 botUserKey 가 같아도 방별로 스냅샷된다(uniq 충돌 없음)")
    fun sameUserSameBotUserKeyAcrossRooms() {
        cleanup()
        val u = userRepository.save(User(appUserId = "same").apply { score = 70 })
        // botUserKey 가 방 간 겹치는 경우 — bot_key 없는 유니크였다면 여기서 충돌해 reset() 이 통째로 롤백된다.
        botUserRepository.save(BotUser(user = u, botUserKey = "sameKey", botKey = "botA", score = 10))
        botUserRepository.save(BotUser(user = u, botUserKey = "sameKey", botKey = "botB", score = 20))

        seasonReset.reset()

        val rows = seasonBotScoreRepository.findByYearMonth(ym).filter { it.botUserKey == "sameKey" }
        assertEquals(2, rows.size)
        assertEquals(setOf("botA", "botB"), rows.map { it.botKey }.toSet())
        rows.forEach { assertEquals(70, it.score) } // score = user.score(70), BotUser.score 아님
        cleanup()
    }

    @Test
    @DisplayName("여러 방에서 botUserKey 가 다르면 각 키로 방별 스냅샷된다")
    fun sameUserDifferentBotUserKeyAcrossRooms() {
        cleanup()
        val u = userRepository.save(User(appUserId = "diff").apply { score = 55 })
        // 카카오 원칙(봇 기준 키)대로 방마다 다른 botUserKey 가 발급되는 경우.
        botUserRepository.save(BotUser(user = u, botUserKey = "keyA", botKey = "botA", score = 10))
        botUserRepository.save(BotUser(user = u, botUserKey = "keyB", botKey = "botB", score = 20))

        seasonReset.reset()

        val rows = seasonBotScoreRepository.findByYearMonth(ym).filter { it.userId == u.id }
        assertEquals(2, rows.size)
        assertEquals(setOf("keyA", "keyB"), rows.map { it.botUserKey }.toSet())
        assertEquals(setOf("botA", "botB"), rows.map { it.botKey }.toSet())
        rows.forEach { assertEquals(55, it.score) } // score = user.score(55)
        cleanup()
    }
}
