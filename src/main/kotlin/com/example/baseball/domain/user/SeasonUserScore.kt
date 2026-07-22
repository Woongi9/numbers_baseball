package com.example.baseball.domain.user

import jakarta.persistence.*

/**
 * 전역(User) 시즌 스냅샷 한 행. 매월 리셋 직전 SeasonReset 이 채운다(읽기 전용 이력).
 * user_id 는 users.id 를 가리키는 논리 FK(값 컬럼) — 조회 편의상 JPA 연관을 걸지 않는다.
 */
@Entity
@Table(
    name = "season_user_scores",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_season_user_ym_user", columnNames = ["year_month", "user_id"]),
    ],
    indexes = [Index(name = "idx_season_user_ym", columnList = "year_month")],
)
class SeasonUserScore(
    @Column(name = "year_month", nullable = false, length = 7)
    val yearMonth: String,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "score", nullable = false)
    val score: Int,

    // 스냅샷 시점 상위 백분위(작을수록 상위). 코호트 표본 부족(MIN_SAMPLE 미만)이면 null.
    @Column(name = "top_percent")
    val topPercent: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
