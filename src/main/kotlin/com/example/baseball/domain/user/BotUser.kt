package com.example.baseball.domain.user

import jakarta.persistence.*

@Entity
@Table(
    name = "bot_users",
    indexes = [
        // 봇별 랭킹: WHERE bot_key = ? ORDER BY score DESC 를 정렬까지 인덱스로 처리.
        Index(name = "idx_bot_users_bot_key_score", columnList = "bot_key, score"),
    ],
    // 한 봇(채팅방) 안에서 같은 유저는 1행. getOrCreate 동시성 중복의 DB 방어선이자
    // findByBotKeyAndBotUserKey 조회의 유니크 인덱스 역할도 겸한다.
    uniqueConstraints = [
        UniqueConstraint(name = "uk_bot_users_bot_key_user_key", columnNames = ["bot_key", "bot_user_key"]),
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
