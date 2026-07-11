package com.example.baseball.service

import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 시즌(월간) 점수 리셋. 매월 1일 0시(Asia/Seoul) User/BotUser 점수를 0으로 초기화한다.
 * 점수 시스템은 "매월 0 리셋 시즌제" 전제(ScoreCalculator 참고)라 이 배치가 없으면 점수가 영원히 누적된다.
 *
 * reset() 은 스케줄 진입점이자 테스트가 직접 호출하는 트리거다(멱등 — 이미 0이면 행 0건).
 */
@Component
class SeasonReset(
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Seoul")
    @Transactional
    fun reset() {
        val started = System.currentTimeMillis()
        val users = userRepository.resetAllScores()
        val botUsers = botUserRepository.resetAllScores()
        log.info("evt=season_reset users={} bot_users={} elapsedMs={}", users, botUsers, System.currentTimeMillis() - started)
    }
}
