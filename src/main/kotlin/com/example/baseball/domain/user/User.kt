package com.example.baseball.domain.user

import jakarta.persistence.*

@Entity
@Table(name = "users")
class User (
    @Column(name = "app_user_id")
    val appUserId: String,
    ) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // score 합산은 매달 초에 초기화
    @Column(name = "scores")
    var score: Int = 0;
}
