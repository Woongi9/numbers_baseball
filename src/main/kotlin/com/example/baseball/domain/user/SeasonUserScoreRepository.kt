package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface SeasonUserScoreRepository : JpaRepository<SeasonUserScore, Long> {
    /** 그 달 스냅샷이 이미 있는지. SeasonReset 멱등 가드(같은 달 재실행 방지)에 쓴다. */
    fun existsByYearMonth(yearMonth: String): Boolean

    /** 특정 달 전체 스냅샷(테스트/조회용). */
    fun findByYearMonth(yearMonth: String): List<SeasonUserScore>
}
