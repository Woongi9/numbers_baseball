package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

// User.id 는 Long PK 이므로 JpaRepository 의 ID 타입도 Long 이어야 한다.
interface UserRepository : JpaRepository<User, Long> {

    /** 전역 식별자(appUserId)로 1건 조회. score 적립 시 getOrCreate 의 조회 단계에 사용. */
    fun findByAppUserId(appUserId: String): User?

    /**
     * 주어진 점수보다 '엄격히 높은' 유저 수. 상위 백분위(PLAN 9-P)의 "나보다 위에 몇 명?" 카운트.
     * `WHERE scores > ?` 로 변환되며, users(scores) 인덱스가 있으면 인덱스 범위 스캔으로 테이블 접근을 피한다.
     * (동점은 제외되어 공동순위로 묶인다.)
     */
    fun countByScoreGreaterThan(score: Int): Long

    /** 시즌(월간) 리셋: 전역 점수를 일괄 0으로. 반환값은 초기화된 행 수(로깅용). */
    @Modifying
    @Query("UPDATE User u SET u.score = 0 WHERE u.score <> 0")
    fun resetAllScores(): Int
}
