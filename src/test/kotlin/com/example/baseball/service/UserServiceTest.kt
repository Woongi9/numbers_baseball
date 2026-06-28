package com.example.baseball.service

import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.User
import com.example.baseball.domain.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("UserService.accrue - 점수 적립(getOrCreate + 누적)")
class UserServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val botUserRepository = mockk<BotUserRepository>()
    private val sut = UserService(userRepository, botUserRepository)

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
            every { userRepository.findByAppUserId("app-1") } returns user

            val total = sut.accrue(appUserId = "app-1", botKey = null, botUserKey = "u1", gain = 65)

            assertEquals(1065, total)
            assertEquals(1065, user.score)
            verify(exactly = 0) { userRepository.save(any()) } // 더티체킹 → 명시 save 불필요
        }

        @Test
        @DisplayName("유저가 없으면 새로 만들어 적립한다(getOrCreate)")
        fun createsWhenMissing() {
            every { userRepository.findByAppUserId("app-new") } returns null
            stubSaves()

            val saved = slot<User>()
            every { userRepository.save(capture(saved)) } answers { saved.captured }

            val total = sut.accrue(appUserId = "app-new", botKey = null, botUserKey = "u1", gain = 50)

            assertEquals("app-new", saved.captured.appUserId)
            assertEquals(50, total)
            verify(exactly = 1) { userRepository.save(any()) }
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

            sut.accrue(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1", gain = 30)

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

            sut.accrue(appUserId = "app-1", botKey = "bot-1", botUserKey = "u1", gain = 40)

            assertEquals("bot-1", saved.captured.botKey)
            assertEquals("u1", saved.captured.botUserKey)
            assertEquals(40, saved.captured.score)
        }

        @Test
        @DisplayName("botKey 가 null 이면 BotUser 는 건드리지 않는다")
        fun skipsBotUserWhenNoBotKey() {
            val user = User(appUserId = "app-1").apply { score = 0 }
            every { userRepository.findByAppUserId("app-1") } returns user

            sut.accrue(appUserId = "app-1", botKey = null, botUserKey = "u1", gain = 40)

            verify(exactly = 0) { botUserRepository.findByBotKeyAndBotUserKey(any(), any()) }
            verify(exactly = 0) { botUserRepository.save(any()) }
        }
    }

    @Test
    @DisplayName("gain 이 음수면 예외(누적 점수 모델은 감점이 없다)")
    fun rejectsNegativeGain() {
        assertThrows(IllegalArgumentException::class.java) {
            sut.accrue(appUserId = "app-1", botKey = null, botUserKey = "u1", gain = -1)
        }
    }
}
