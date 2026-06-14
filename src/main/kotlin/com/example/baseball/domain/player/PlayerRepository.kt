package com.example.baseball.domain.player

import org.springframework.data.jpa.repository.JpaRepository

interface PlayerRepository : JpaRepository<Player, Int> {
}