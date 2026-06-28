package com.example.baseball.domain.user

import jakarta.persistence.*

@Entity
@Table(
    name = "users",
    // 전역 식별자(appUserId)는 유저당 1행이어야 한다. getOrCreate 동시성(중복 INSERT)의 DB 최종 방어선.
    uniqueConstraints = [UniqueConstraint(name = "uk_users_app_user_id", columnNames = ["app_user_id"])],
)
class User (
    @Column(name = "app_user_id", nullable = false)
    val appUserId: String,
    ) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // score 합산은 매달 초에 초기화
    @Column(name = "scores")
    var score: Int = 0;
}
