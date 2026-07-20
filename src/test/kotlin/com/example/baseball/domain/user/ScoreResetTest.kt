package com.example.baseball.domain.user

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals

@DataJpaTest
@DisplayName("시즌 리셋 - resetAllScores() 벌크 초기화")
class ScoreResetTest {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var botUserRepository: BotUserRepository
    @Autowired lateinit var em: EntityManager

    @Test
    @DisplayName("User/BotUser 점수를 모두 0으로 초기화한다")
    fun resetsAllScoresToZero() {
        val user = userRepository.save(User(appUserId = "a").apply { score = 100 })
        userRepository.save(User(appUserId = "b").apply { score = 50 })
        botUserRepository.save(BotUser(user = user, botUserKey = "u1", botKey = "bot", score = 30))

        userRepository.resetAllScores()
        botUserRepository.resetAllScores()
        em.clear() // 벌크 UPDATE 는 영속성 컨텍스트를 우회하므로 캐시된 엔티티를 비워 재조회한다.

        assertEquals(0, userRepository.findAll().sumOf { it.score })
        assertEquals(0, botUserRepository.findAll().sumOf { it.score })
    }
}
