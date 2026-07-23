package com.example.baseball.service

import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.User
import com.example.baseball.domain.user.UserRepository
import com.example.baseball.dto.ChatIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("UserService.accrue - 점수 적립(getOrCreate + 누적)")
class UserServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val botUserRepository = mockk<BotUserRepository>()
    private val sut = UserService(userRepository, botUserRepository)

    private val identity = ChatIdentity(appUserId = "app-1", botUserKey = "u1", botKey = "bot-1")

    /** save(entity) 가 들어온 엔티티를 그대로 반환하도록(영속화 흉내) 설정. */
    private fun stubSaves() {
        every { userRepository.save(any()) } answers { firstArg() }
        every { botUserRepository.save(any()) } answers { firstArg() }
    }

    @Nested
    @DisplayName("User(전역) 적립")
    inner class GlobalUser {

        @Test
        @DisplayName("기존 유저가 있으면 생성 없이 score 에 gain 을 더하고 누적값을 반환한다")
        fun addsToExistingUser() {
            val user = User(appUserId = "app-1").apply { score = 1000 }
            val botUser = BotUser(user = user, botUserKey = "u1", botKey = "bot-1", score = 0)
            every { userRepository.findByAppUserId("app-1") } returns user
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            val total = sut.accrue(identity, gain = 65)

            assertEquals(1065, total)
            assertEquals(1065, user.score)
            verify(exactly = 0) { userRepository.save(any()) } // 더티체킹 → 명시 save 불필요
        }

        @Test
        @DisplayName("유저가 없으면 새로 만들어 적립한다(getOrCreate)")
        fun createsWhenMissing() {
            val newIdentity = ChatIdentity(appUserId = "app-new", botUserKey = "u1", botKey = "bot-1")
            every { userRepository.findByAppUserId("app-new") } returns null
            every { userRepository.findByAppUserId("u1") } returns null
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns null
            stubSaves()

            val saved = slot<User>()
            every { userRepository.save(capture(saved)) } answers { saved.captured }

            val total = sut.accrue(newIdentity, gain = 50)

            assertEquals("app-new", saved.captured.appUserId)
            assertEquals(50, total)
        }
    }

    @Nested
    @DisplayName("BotUser(봇 랭킹) 적립")
    inner class BotUserScope {

        @Test
        @DisplayName("botKey 가 있으면 봇 유저에도 같은 gain 을 적립한다")
        fun accruesBotUserToo() {
            val user = User(appUserId = "app-1").apply { score = 100 }
            val botUser = BotUser(user = user, botUserKey = "u1", botKey = "bot-1", score = 200)
            every { userRepository.findByAppUserId("app-1") } returns user
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            sut.accrue(identity, gain = 30)

            assertEquals(130, user.score)
            assertEquals(230, botUser.score)
        }

        @Test
        @DisplayName("봇 유저가 없으면 새로 만들어 적립한다")
        fun createsBotUserWhenMissing() {
            val user = User(appUserId = "app-1").apply { score = 0 }
            every { userRepository.findByAppUserId("app-1") } returns user
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns null
            val saved = slot<BotUser>()
            every { botUserRepository.save(capture(saved)) } answers { saved.captured }

            sut.accrue(identity, gain = 40)

            assertEquals("bot-1", saved.captured.botKey)
            assertEquals("u1", saved.captured.botUserKey)
            assertEquals(40, saved.captured.score)
        }
    }

    @Test
    @DisplayName("gain 이 음수면 예외(누적 점수 모델은 감점이 없다)")
    fun rejectsNegativeGain() {
        assertThrows(IllegalArgumentException::class.java) {
            sut.accrue(identity, gain = -1)
        }
    }

    @Nested
    @DisplayName("register - 시작 시 참가자 행 보장(점수 변동 없음)")
    inner class Register {

        @Test
        @DisplayName("없으면 User·BotUser 를 score 0 으로 생성한다")
        fun createsBothWhenMissing() {
            every { userRepository.findByAppUserId("app-1") } returns null
            every { userRepository.findByAppUserId("u1") } returns null
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns null
            val savedUser = slot<User>()
            val savedBot = slot<BotUser>()
            every { userRepository.save(capture(savedUser)) } answers { savedUser.captured }
            every { botUserRepository.save(capture(savedBot)) } answers { savedBot.captured }

            sut.register(identity)

            assertEquals("app-1", savedUser.captured.appUserId)
            assertEquals(0, savedUser.captured.score) // 점수 변동 없음
            assertEquals("bot-1", savedBot.captured.botKey)
            assertEquals(0, savedBot.captured.score)
        }

        @Test
        @DisplayName("이미 있으면 생성하지 않고 점수도 그대로 둔다")
        fun noCreateWhenExisting() {
            val user = User(appUserId = "app-1").apply { score = 500 }
            val botUser = BotUser(user = user, botUserKey = "u1", botKey = "bot-1", score = 300)
            every { userRepository.findByAppUserId("app-1") } returns user
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            sut.register(identity)

            assertEquals(500, user.score)   // 변동 없음
            assertEquals(300, botUser.score)
            verify(exactly = 0) { userRepository.save(any()) }
            verify(exactly = 0) { botUserRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("percentileOf - 전역 상위 백분위")
    inner class PercentileOf {

        @Test
        @DisplayName("COUNT(score>my)·COUNT(*) 결과를 PercentileCalculator 규칙대로 매핑한다")
        fun mapsCounts() {
            every { userRepository.countByScoreGreaterThan(1065) } returns 4L // 나보다 위 4명
            every { userRepository.count() } returns 100L

            val p = sut.percentileOf(1065)!!

            assertEquals(5, p.rank)        // 4 + 1
            assertEquals(100, p.total)
            assertEquals(5, p.topPercent)  // ceil(5*100/100)
        }

        @Test
        @DisplayName("표본 부족(total < MIN_SAMPLE)이면 null")
        fun tooFewSamplesIsNull() {
            every { userRepository.countByScoreGreaterThan(any()) } returns 0L
            every { userRepository.count() } returns 1L // 혼자

            assertNull(sut.percentileOf(50))
        }
    }

    @Nested
    @DisplayName("appUserId 지연 이관 - 임시 botUserKey 행 개명")
    inner class LazyMigration {

        @Test
        @DisplayName("진짜 appUserId 도착 시 임시행(appUserId==botUserKey)을 개명하고 점수를 유지한다")
        fun renamesTempRowOnRealAppUserId() {
            // 임시 기간에 botUserKey("u1")로 만들어진 행(점수 700)
            val temp = User(appUserId = "u1").apply { score = 700 }
            every { userRepository.findByAppUserId("app-real") } returns null
            every { userRepository.findByAppUserId("u1") } returns temp
            val botUser = BotUser(user = temp, botUserKey = "u1", botKey = "bot-1", score = 700)
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            val realIdentity = ChatIdentity(appUserId = "app-real", botUserKey = "u1", botKey = "bot-1")
            val total = sut.accrue(realIdentity, gain = 30)

            assertEquals("app-real", temp.appUserId) // 개명됨
            assertEquals(730, total)                 // 700 유지 + 30
            verify(exactly = 0) { userRepository.save(any()) } // 새 행 생성 안 함
        }

        @Test
        @DisplayName("진짜행이 이미 있으면 이관하지 않고 그 행을 쓴다(중복 방지)")
        fun usesExistingRealRow() {
            val real = User(appUserId = "app-real").apply { score = 50 }
            every { userRepository.findByAppUserId("app-real") } returns real
            val botUser = BotUser(user = real, botUserKey = "u1", botKey = "bot-1", score = 50)
            every { botUserRepository.findByBotKeyAndBotUserKey("bot-1", "u1") } returns botUser

            val realIdentity = ChatIdentity(appUserId = "app-real", botUserKey = "u1", botKey = "bot-1")
            sut.accrue(realIdentity, gain = 10)

            assertEquals(60, real.score)
            verify(exactly = 0) { userRepository.findByAppUserId("u1") } // 임시행 탐색 안 함
        }
    }
}
