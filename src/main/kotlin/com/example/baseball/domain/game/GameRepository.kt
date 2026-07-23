package com.example.baseball.domain.game

import org.springframework.data.jpa.repository.JpaRepository

interface GameRepository : JpaRepository<Game, Long> {

    /**
     * 방의 진행중 게임 전부. 정상 상태에선 0 또는 1건이지만 동시 시작으로 2건 이상 남을 수 있어
     * 리스트로 받아 startGame 이 전건 정리한다(방당 1건 불변식은 DB 가 아니라 거기서 수렴한다).
     */
    fun findAllByBotKeyAndStatus(botKey: String, status: GameStatus): List<Game>

    /** 방의 진행중 게임 중 최신 1건. 유령이 남아 있어도 가장 최근 게임으로 판정한다. */
    fun findFirstByBotKeyAndStatusOrderByIdDesc(botKey: String, status: GameStatus): Game?
}
