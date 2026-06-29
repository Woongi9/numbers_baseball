package com.example.baseball.service

import com.example.baseball.domain.user.BotUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 랭킹 한 줄(순위·표시 라벨·점수). 컨트롤러가 이 목록으로 텍스트를 만든다. */
data class RankEntry(
    val rank: Int,
    val label: String,
    val score: Int,
)

/**
 * 랭킹 조회 전용 서비스(읽기). 게임 진행·점수 적립과 책임을 분리한다(단일 책임).
 * 점수 적립(STEP 9)이 연결되면 이 서비스는 그대로 실데이터를 읽게 된다.
 */
@Service
class RankingService(
    private val botUserRepository: BotUserRepository,
) {
    /**
     * 현재 채팅방(botKey) 내 점수 TOP 10.
     * 정렬·LIMIT 은 리포지토리 파생 쿼리가 담당 → 5초 제한 대비 경량 조회.
     */
    @Transactional(readOnly = true)
    fun getBotRanking(botKey: String): List<RankEntry> =
        botUserRepository.findTop10ByBotKeyOrderByScoreDesc(botKey)
            .mapIndexed { index, botUser ->
                RankEntry(
                    rank = index + 1,
                    // 닉네임 컬럼 도입(STEP 9/A) 전까지 카카오 키를 그대로 라벨로 쓴다.
                    label = botUser.botUserKey,
                    score = botUser.score,
                )
            }
}
