package com.example.baseball.service

import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.UserRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

data class ScoreOutcomeByBot (
    val botKey: String,
    val botUserKey: String,
    val score: Int,
)

data class ScoreOutcomeByUser(
    val appUserId: String,
    val score: Int,
)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
) {

    @Transactional
    fun getScoresByBotKey(botKey: String): List<ScoreOutcomeByBot> {
        return botUserRepository.findByBotKey(botKey)
            .map { botUser ->
                ScoreOutcomeByBot(
                    botKey = botUser.botKey,
                    botUserKey = botUser.botUserKey,
                    score = botUser.score
                ) }
            .toList();
    }

    @Transactional
    fun getScoreByUser(kakaoUserAppKey: String): ScoreOutcomeByUser? {
        return null;
    }
}
