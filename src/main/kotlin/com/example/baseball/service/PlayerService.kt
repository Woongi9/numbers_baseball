package com.example.baseball.service

import com.example.baseball.domain.player.BotPlayerRepository
import com.example.baseball.domain.player.PlayerRepository
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
class PlayerService(
    private val playerRepository: PlayerRepository,
    private val botPlayerRepository: BotPlayerRepository,
) {

    @Transactional
    fun getScoresByBotKey(botKey: String): List<ScoreOutcomeByBot> {
        return botPlayerRepository.findByBotKey(botKey)
            .map { botPlayer ->
                ScoreOutcomeByBot(
                    botKey = botPlayer.botKey,
                    botUserKey = botPlayer.botUserKey,
                    score = botPlayer.score
                ) }
            .toList();

    }

    @Transactional
    fun getScoreByUser(kakaoUserAppKey: String): ScoreOutcomeByUser? {
        return null;
    }
}
