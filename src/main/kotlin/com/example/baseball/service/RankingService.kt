package com.example.baseball.service

import com.example.baseball.domain.user.BotUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 랭킹 한 줄(순위·사용자 키·점수). 컨트롤러가 이 목록으로 텍스트를 만든다.
 * botUserKey 는 오픈채팅 멘션(extra.mentions)의 id 로 쓰여, 카카오가 실제 닉네임(@사용자)으로 치환한다.
 */
data class RankEntry(
    val rank: Int,
    val botUserKey: String,
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
                    // 오픈채팅 멘션 id 로 넘길 원본 키. 카카오가 이 키의 실제 닉네임으로 치환한다(STEP 12 배포 피드백).
                    botUserKey = botUser.botUserKey,
                    score = botUser.score,
                )
            }
}
