package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface BotUserRepository : JpaRepository<BotUser, Long> {
    fun findByBotKey(botKey: String): MutableList<BotUser>

    /** 봇(채팅방) 내 특정 유저 1건 조회. score 적립 시 getOrCreate 의 조회 단계에 사용. */
    fun findByBotKeyAndBotUserKey(botKey: String, botUserKey: String): BotUser?

    /**
     * 봇(채팅방) 내 랭킹 TOP 10. 점수 기준은 채팅방별 점수(BotUser.score)가 아닌
     * 전역 점수(User.score)다. `user` 를 JOIN 해 `ORDER BY u.score DESC LIMIT 10` 으로 변환되며,
     * (bot_key, score) 복합 인덱스는 정렬에는 더 이상 쓰이지 않고 bot_key 필터링에만 쓰인다.
     */
    fun findTop10ByBotKeyOrderByUser_ScoreDesc(botKey: String): List<BotUser>

    /** 봇(채팅방) 전체 참가자 수. 랭킹의 상위 백분위(RankTitle 뱃지) 계산의 모집단으로 쓴다. */
    fun countByBotKey(botKey: String): Long

    /** 시즌(월간) 리셋: 봇별 점수를 일괄 0으로. 반환값은 초기화된 행 수(로깅용). */
    @Modifying
    @Query("UPDATE BotUser b SET b.score = 0 WHERE b.score <> 0")
    fun resetAllScores(): Int
}
