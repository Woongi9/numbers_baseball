package com.example.baseball.service

import com.example.baseball.common.TraceKeys
import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.User
import com.example.baseball.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ScoreOutcomeByBot (
    val botKey: String,
    val botUserKey: String,
    val score: Int,
)

data class ScoreOutcomeByUser(
    val appUserId: String,
    val score: Int,
)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
) {

    private val log = LoggerFactory.getLogger(UserService::class.java)

    @Transactional
    fun getScoresByBotKey(botKey: String): List<ScoreOutcomeByBot> {
        return botUserRepository.findByBotKey(botKey)
            .map { botUser ->
                ScoreOutcomeByBot(
                    botKey = botUser.botKey,
                    botUserKey = botUser.botUserKey,
                    score = botUser.score
                ) }
            .toList();
    }

    @Transactional
    fun getScoreByUser(kakaoUserAppKey: String): ScoreOutcomeByUser? {
        return null;
    }

    /**
     * 승리 시 획득 점수(gain)를 전역 유저(User)와 봇 내 유저(BotUser)에 누적한다.
     *
     * 동작 순서:
     *  1. appUserId 로 User getOrCreate → 전역 누적 score += gain
     *  2. botKey 가 있으면 (botKey, botUserKey) 로 BotUser getOrCreate → 봇 랭킹용 score += gain
     *     (botKey 가 null 이면 채팅방 식별 불가 → 전역 점수만 적립)
     *  3. 같은 트랜잭션에서 더티체킹으로 flush → 적립과 게임 상태 전이가 원자적으로 커밋된다.
     *
     * @return 적립 후 전역 누적 score(응답의 "이전 → 현재" 표기에 사용).
     *
     * 동시성 메모: getOrCreate 는 "조회 후 없으면 생성"이라 같은 유저의 첫 요청 2건이 거의 동시에
     * 들어오면 중복 INSERT 가능성이 이론상 존재한다. 카카오 챗봇은 유저당 발화가 직렬이라 실질
     * 위험은 낮고, users(app_user_id)·bot_users(bot_key,bot_user_key) UNIQUE 제약이 DB 최종
     * 방어선이다. 규모가 커지면 INSERT ... ON DUPLICATE KEY(upsert) 또는 Redis 로 전환한다.
     */
    @Transactional
    fun accrue(appUserId: String, botKey: String?, botUserKey: String, gain: Int): Int {
        require(gain >= 0) { "gain 은 0 이상이어야 합니다. (입력: $gain)" }

        val user = getOrCreateUser(appUserId)
        user.score += gain

        if (botKey != null) {
            getOrCreateBotUser(user, botKey, botUserKey).score += gain
        }
        return user.score
    }

    /**
     * 게임 시작 시점에 참가자(User/BotUser) 행만 보장한다(점수 변동 없음, PLAN 9-F 증상 2).
     *
     * 승리해야만 행이 생기던 문제를 막아, 아직 점수가 없는 참여자도 추적·집계에 포함시킨다.
     * `accrue` 와 동일한 getOrCreate 헬퍼를 공유하므로 생성 규칙(키·중복 방어선)이 한 곳에서 관리된다.
     *
     * @param botKey null 이면 채팅방 식별 불가 → 전역 User 만 보장한다.
     */
    @Transactional
    fun register(appUserId: String, botKey: String?, botUserKey: String) {
        val user = getOrCreateUser(appUserId)
        if (botKey != null) {
            getOrCreateBotUser(user, botKey, botUserKey)
        }
    }

    /**
     * 전역 유저 조회, 없으면 생성. UNIQUE(app_user_id) 가 동시성 중복의 최종 방어선.
     * 신규 생성 시 가입/유입 추적용 new_user 이벤트를 traceId 와 함께 남긴다(임팩트 측정).
     */
    private fun getOrCreateUser(appUserId: String): User =
        userRepository.findByAppUserId(appUserId)
            ?: userRepository.save(User(appUserId = appUserId)).also {
                log.info(
                    "evt=new_user traceId={} appUserId={}",
                    MDC.get(TraceKeys.TRACE_ID),
                    appUserId,
                )
            }

    /** 봇 내 유저 조회, 없으면 생성. UNIQUE(bot_key, bot_user_key) 가 동시성 중복의 최종 방어선. */
    private fun getOrCreateBotUser(user: User, botKey: String, botUserKey: String): BotUser =
        botUserRepository.findByBotKeyAndBotUserKey(botKey, botUserKey)
            ?: botUserRepository.save(BotUser(user = user, botUserKey = botUserKey, botKey = botKey))

    /**
     * 전역(User.score) 기준 상위 백분위 조회(PLAN 9-P).
     *
     * 동작 순서:
     *  1. higher = COUNT(scores > myScore)   // 나보다 위에 몇 명? (인덱스 범위 스캔, 동점 제외)
     *  2. total  = COUNT(*)
     *  3. PercentileCalculator.of(higher, total) → 표본 부족(total<MIN_SAMPLE)이면 null
     *
     * 적립과 같은 트랜잭션에서 호출해야 방금 올린 점수가 반영된다(guess()의 @Transactional 안에서 합류).
     *
     * @param myScore 적립 후 내 전역 누적 점수.
     * @return 백분위 정보(표본 부족이면 null).
     */
    @Transactional(readOnly = true)
    fun percentileOf(myScore: Int): Percentile? {
        val higher = userRepository.countByScoreGreaterThan(myScore).toInt()
        val total = userRepository.count().toInt()
        return PercentileCalculator.of(higher = higher, total = total)
    }
}
