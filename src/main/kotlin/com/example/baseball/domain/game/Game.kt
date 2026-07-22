package com.example.baseball.domain.game

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 한 판의 숫자야구 게임 세션.
 *
 * - 상태 전이(승리/포기)는 외부에서 status를 직접 바꾸지 않고 도메인 메서드로만 수행한다.
 *   → 항상 finishedAt이 함께 채워져 데이터 정합성이 깨지지 않는다.
 * - (bot_key, status) 복합 인덱스: bot_key 컬럼엔 appUserId 값이 들어간다(게임은 유저 단위 스코프 — 이름과 달리 채팅방 키가 아님). 매 요청마다 "이 유저의 PLAYING 게임" 조회가 일어나므로 필수.
 */
@Entity
@Table(
    name = "game",
    indexes = [Index(name = "idx_game_bot_key_status", columnList = "bot_key, status")],
)
class Game(
    @Column(nullable = false)
    val botKey: String,

    @Column(nullable = false)
    val answer: String,

    @Column(name = "game_difficulty", nullable = false)
    @Enumerated(EnumType.STRING)
    val gameDifficulty: GameDifficulty = GameDifficulty.NORMAL,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    var tries: Int = 0
        protected set

    @Column(nullable = false)
    var score: Int = (100 * gameDifficulty.multiplier()).toInt();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: GameStatus = GameStatus.PLAYING
        protected set

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()

    var finishedAt: LocalDateTime? = null
        protected set

    val isPlaying: Boolean get() = status == GameStatus.PLAYING

    /** 추측 1회 기록. 진행 중일 때만 허용. */
    fun recordTry() {
        check(isPlaying) { "이미 종료된 게임입니다." }
        tries++
    }

    /** 정답을 맞혀 종료. */
    fun win() {
        finish(GameStatus.WON)
    }

    /** 사용자가 포기하여 종료. */
    fun giveUp() {
        finish(GameStatus.GIVEUP)
    }

    private fun finish(next: GameStatus) {
        check(isPlaying) { "이미 종료된 게임입니다." }
        status = next
        finishedAt = LocalDateTime.now()
    }
}
