package com.example.baseball.service

import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.SeasonBotScore
import com.example.baseball.domain.user.SeasonBotScoreRepository
import com.example.baseball.domain.user.SeasonUserScore
import com.example.baseball.domain.user.SeasonUserScoreRepository
import com.example.baseball.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.time.ZoneId

/**
 * 시즌(월간) 점수 리셋 + 리셋 직전 스냅샷 보존.
 * 매월 1일 0시(Asia/Seoul) 실행 → 마감 대상은 직전 달(YearMonth.minusMonths(1)).
 *
 * 순서(같은 @Transactional 안 = 원자적):
 *  1. 멱등 가드: 그 달 스냅샷이 이미 있으면 스킵(재실행/수동 트리거 안전).
 *  2. score>0 유저/봇유저를 상위 백분위(PercentileCalculator 재사용)와 함께 스냅샷.
 *  3. 기존 resetAllScores() 로 User/BotUser.score 를 0으로.
 */
@Component
class SeasonReset(
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
    private val seasonUserScoreRepository: SeasonUserScoreRepository,
    private val seasonBotScoreRepository: SeasonBotScoreRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seoul = ZoneId.of("Asia/Seoul")

    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Seoul")
    @Transactional
    fun reset() {
        val started = System.currentTimeMillis()
        val ym = YearMonth.now(seoul).minusMonths(1).toString() // "2026-07"

        // 멱등 가드: 그 달 스냅샷이 이미 있으면 스킵. season_user_scores 만 확인해도 되는 이유 —
        // score>0 인 BotUser 는 그 User 도 반드시 전역 스냅샷에 들어가므로 두 테이블은 항상 같이 채워진다.
        // ponytail(ceiling): 이 가드는 "데이터를 스냅샷했나"이지 "실행했나"가 아니다. 마감 달에 score>0
        //   유저가 한 명도 없으면 스냅샷 0건이라 existsByYearMonth 가 계속 false → 같은 달 2차 실행이
        //   이번 달 라이브 점수를 지난달 라벨로 밀어버릴 수 있다. 확률 낮아(빈 달 + 수동/오작동 2차 실행)
        //   run-log 테이블 대신 주석으로 남긴다. 문제되면 "실행 여부" 마커 행으로 승격.
        if (seasonUserScoreRepository.existsByYearMonth(ym)) {
            log.info("evt=season_reset_skip reason=already_snapshotted ym={}", ym)
            return
        }

        val userSnapshots = snapshotUsers(ym)
        val botSnapshots = snapshotBots(ym)

        val users = userRepository.resetAllScores()
        val botUsers = botUserRepository.resetAllScores()
        log.info(
            "evt=season_reset ym={} snap_users={} snap_bots={} reset_users={} reset_bots={} elapsedMs={}",
            ym, userSnapshots, botSnapshots, users, botUsers, System.currentTimeMillis() - started,
        )
    }

    /** 전역 스냅샷. 코호트 = 전체 유저 수(0점 포함, 라이브 percentileOf 와 동일 분모). */
    private fun snapshotUsers(ym: String): Int {
        val all = userRepository.findAll()
        val total = all.size
        val rows = all.filter { it.score > 0 }.map { u ->
            val higher = all.count { it.score > u.score } // 동점 제외(공동순위)
            val topPercent = PercentileCalculator.of(higher = higher, total = total)?.topPercent
            SeasonUserScore(yearMonth = ym, userId = u.id!!, score = u.score, topPercent = topPercent)
        }
        seasonUserScoreRepository.saveAll(rows)
        return rows.size
    }

    /** 채팅방 스냅샷. bot_key 별 코호트 = 그 봇 참가자 수. score = user.score(라이브 랭킹 기준값). */
    private fun snapshotBots(ym: String): Int {
        val all = botUserRepository.findAll()
        val rows = all.groupBy { it.botKey }.flatMap { (botKey, members) ->
            val total = members.size
            members.filter { it.user.score > 0 }.map { bu ->
                val score = bu.user.score
                val higher = members.count { it.user.score > score }
                val topPercent = PercentileCalculator.of(higher = higher, total = total)?.topPercent
                SeasonBotScore(
                    yearMonth = ym, botKey = botKey, botUserKey = bu.botUserKey,
                    userId = bu.user.id!!, score = score, topPercent = topPercent,
                )
            }
        }
        seasonBotScoreRepository.saveAll(rows)
        return rows.size
    }
}
