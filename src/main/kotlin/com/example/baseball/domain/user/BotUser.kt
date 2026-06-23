package com.example.baseball.domain.user

import jakarta.persistence.*

@Entity
@Table(
    name = "bot_users",
    indexes = [
        // 봇별 랭킹: WHERE bot_key = ? ORDER BY score DESC 를 정렬까지 인덱스로 처리.
        Index(name = "idx_bot_users_bot_key_score", columnList = "bot_key, score"),
    ],
)
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
