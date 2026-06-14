package com.example.baseball.domain.player

import org.springframework.data.jpa.repository.JpaRepository

interface BotPlayerRepository : JpaRepository<BotPlayer, Long> {
    fun findByBotKey(botKey: String): MutableList<BotPlayer>
}