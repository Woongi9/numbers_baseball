package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface BotUserRepository : JpaRepository<BotUser, Long> {
    fun findByBotKey(botKey: String): MutableList<BotUser>

    /** 봇(채팅방) 내 특정 유저 1건 조회. score 적립 시 getOrCreate 의 조회 단계에 사용. */
    fun findByBotKeyAndBotUserKey(botKey: String, botUserKey: String): BotUser?

    /**
     * 봇(채팅방) 내 점수 랭킹 TOP 10.
     * 파생 쿼리가 `WHERE bot_key = ? ORDER BY score DESC LIMIT 10` 으로 변환된다.
     * 복합 인덱스 (bot_key, score) 가 있으면 정렬까지 인덱스로 처리 → 파일정렬(filesort) 회피.
     */
    fun findTop10ByBotKeyOrderByScoreDesc(botKey: String): List<BotUser>

    /** 봇(채팅방) 전체 참가자 수. 랭킹의 상위 백분위(RankTitle 뱃지) 계산의 모집단으로 쓴다. */
    fun countByBotKey(botKey: String): Long
}
