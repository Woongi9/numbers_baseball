package com.example.baseball.domain.player

import jakarta.persistence.*

@Entity
@Table(name = "players")
class Player (
    @Column(name = "kakao_app_key")
    val kakaoAppKey: String,
    ) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // score 합산은 매달 초에 초기화
    @Column(name = "scores")
    var score: Int = 0;
}