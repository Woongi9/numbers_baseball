package com.example.baseball.domain.game

import org.springframework.data.jpa.repository.JpaRepository

interface GameRepository : JpaRepository<Game, Long> {

    /** 해당 유저의 특정 상태 게임 1건 조회. 진행중(PLAYING) 게임 찾기에 사용. */
    fun findFirstByBotKeyAndStatus(userId: String, status: GameStatus): Game?
}
