package com.example.baseball.domain.player

import jakarta.persistence.*

@Entity
@Table(name = "bot_players")
class BotPlayer (
    @JoinColumn(name = "player_id")
    @ManyToOne(fetch = FetchType.LAZY)
    val player: Player,

    @Column(name = "bot_user_key")
    val botUserKey: String,

    @Column(name = "bot_key")
    val botKey: String,

    @Column(name = "score", nullable = false)
    var score: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}