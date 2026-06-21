package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface BotUserRepository : JpaRepository<BotUser, Long> {
    fun findByBotKey(botKey: String): MutableList<BotUser>
}
