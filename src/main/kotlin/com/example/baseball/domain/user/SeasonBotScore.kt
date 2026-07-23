package com.example.baseball.domain.user

import jakarta.persistence.*

/**
 * 채팅방(BotUser) 시즌 스냅샷 한 행. score 는 라이브 랭킹이 실제로 정렬/표시에 쓰는 user.score 다
 * (BotUser.score 가 아님 — 지난 시즌 순위를 유저가 봤던 화면 그대로 재현하기 위함).
 */
@Entity
@Table(
    name = "season_bot_scores",
    uniqueConstraints = [
        // bot_user_key 는 카카오 user.id(방마다 동일 인물은 같은 값)라 bot_key 를 반드시 포함해야 한다.
        // 빼면 한 유저가 두 채팅방에서 score>0 일 때 uniq 충돌로 reset() 트랜잭션이 통째로 롤백된다(BotUser 의 (bot_key, bot_user_key) 유니크와 같은 이유).
        UniqueConstraint(name = "uk_season_bot_ym_botkey_botuser", columnNames = ["season_ym", "bot_key", "bot_user_key"]),
    ],
    indexes = [Index(name = "idx_season_bot_ym_botkey", columnList = "season_ym, bot_key")],
)
class SeasonBotScore(
    // MySQL 8.0 예약어(YEAR_MONTH interval 단위)라 실제 컬럼명은 year_month 를 피해야 한다.
    // ddl-auto: validate 인 prod 에서 Hibernate 가 예약어를 자동 이스케이프하지 않아 테이블 생성이 조용히 실패한다.
    @Column(name = "season_ym", nullable = false, length = 7)
    val yearMonth: String,

    @Column(name = "bot_key", nullable = false)
    val botKey: String,

    @Column(name = "bot_user_key", nullable = false)
    val botUserKey: String,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "score", nullable = false)
    val score: Int,

    @Column(name = "top_percent")
    val topPercent: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
