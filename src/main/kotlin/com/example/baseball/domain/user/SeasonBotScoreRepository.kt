package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface SeasonBotScoreRepository : JpaRepository<SeasonBotScore, Long> {
    /** 특정 달 전체 채팅방 스냅샷(테스트/조회용). */
    fun findByYearMonth(yearMonth: String): List<SeasonBotScore>
}
