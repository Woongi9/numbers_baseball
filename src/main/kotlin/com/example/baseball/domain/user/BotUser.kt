package com.example.baseball.domain.user

import jakarta.persistence.*

@Entity
@Table(name = "bot_users")
class BotUser (
    @JoinColumn(name = "user_id")
    @ManyToOne(fetch = FetchType.LAZY)
    val user: User,

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
