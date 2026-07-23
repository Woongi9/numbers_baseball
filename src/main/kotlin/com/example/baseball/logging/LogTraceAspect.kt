package com.example.baseball.logging

import com.example.baseball.common.TraceKeys
import com.example.baseball.controller.SkillCommand
import com.example.baseball.dto.ChatIdentity
import com.example.baseball.dto.SkillRequest
import com.example.baseball.dto.SkillResponse
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 스킬 요청 1건당 한 줄 구조화 로깅 (9-F 증상 4).
 *
 * 남기는 정보: traceId · 출처(botKey) · 사용자(마스킹) · intent · utterance · 성공/실패 ·
 * 응답시간(ms) · slow 여부 · 응답 요약. 응답시간은 **finally** 에서 측정해 예외가 나도 남긴다.
 *
 * 동작 순서:
 *  1. 인자(SkillRequest)에서 ChatIdentity 로 botKey/botUserKey 추출, utterance 로 intent 분류.
 *     appUserId 부재 예외는 여기서 삼킨다 — 로깅이 요청을 죽이면 안 된다.
 *  2. traceId 생성 → MDC 적재(이 요청 안의 모든 하위 로그가 같은 traceId 로 묶임)
 *  3. proceed() 실행 → 성공이면 응답 요약, 예외면 ERROR 기록 후 **재던짐**(advice 가 안내 응답 생성)
 *  4. finally: 응답시간 계산, 레벨 분기(정상 INFO / 느림 WARN / 예외 ERROR), MDC 정리
 *
 * 카카오 5초 타임아웃 대비 SLOW_THRESHOLD_MS 초과 시 WARN 으로 조기 경보한다.
 */
@Aspect
@Component
class LogTraceAspect {

    private val log = LoggerFactory.getLogger("SkillTrace")

    @Around("execution(* com.example.baseball.controller.SkillController.play(..))")
    fun trace(joinPoint: ProceedingJoinPoint): Any? {
        val request = joinPoint.args.firstOrNull() as? SkillRequest
        val identity = request?.let { ChatIdentity.fromOrNull(it) }
        val botKey = identity?.botKey ?: "-"
        val botUserKey = identity?.botUserKey ?: request?.userRequest?.user?.id ?: "-"
        val utterance = request?.userRequest?.utterance?.trim().orEmpty()
        val intent = SkillCommand.classify(utterance).name

        val traceId = UUID.randomUUID().toString().take(TRACE_ID_LEN)
        MDC.put(TraceKeys.TRACE_ID, traceId)
        MDC.put(TraceKeys.BOT_KEY, botKey)
        MDC.put(TraceKeys.BOT_USER, botUserKey)

        // 요청 시작 기록. 처리 도중 죽어도(타임아웃·OOM 등) "들어온 요청"의 흔적은 남는다.
        log.info(
            "phase=START botKey=$botKey user=$botUserKey command=$intent " +
                "utterance=\"${sanitize(utterance).take(SUMMARY_LEN)}\"",
        )

        val startedAt = System.nanoTime()
        var status = "OK"
        var summary = ""
        try {
            val result = joinPoint.proceed()
            summary = summarize(result)
            return result
        } catch (e: Throwable) {
            // 예외를 삼키지 않고 재던진다. 관측만 하고 advice 가 사용자 응답을 만든다.
            // 자릿수·중복 틀린 추측(IllegalArgumentException), 게임 없음(IllegalStateException)은
            // advice 가 안내로 처리하는 '정상 유저 흐름' → REJECTED. 진짜 장애만 ERROR 로 남겨
            // error_count 지표가 서버 에러만 세도록 한다.
            status = if (e is IllegalArgumentException || e is IllegalStateException) {
                "REJECTED(${e.javaClass.simpleName})"
            } else {
                "ERROR(${e.javaClass.simpleName})"
            }
            summary = sanitize(e.message ?: "").take(SUMMARY_LEN)
            throw e
        } finally {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val slow = elapsedMs >= SLOW_THRESHOLD_MS
            val line = "phase=END botKey=$botKey user=$botUserKey command=$intent " +
                "status=$status elapsedMs=$elapsedMs slow=$slow result=\"$summary\""
            when {
                status.startsWith("ERROR") -> log.error(line)
                slow -> log.warn(line)
                else -> log.info(line)   // OK · REJECTED
            }
            MDC.clear()
        }
    }

    /** 응답(SkillResponse)의 첫 줄을 요약으로 뽑는다. */
    private fun summarize(result: Any?): String {
        val text = (result as? SkillResponse)
            ?.template?.outputs?.firstOrNull()?.simpleText?.text ?: return ""
        return sanitize(text).take(SUMMARY_LEN)
    }

    /** 로그 한 줄을 깨뜨리는 개행·따옴표 제거. */
    private fun sanitize(s: String): String =
        s.replace("\n", " ").replace("\"", "'").trim()

    companion object {
        private const val TRACE_ID_LEN = 8
        private const val SUMMARY_LEN = 40

        /** 카카오 5초 타임아웃 대비 조기 경보 임계치(ms). 초과 시 WARN. */
        const val SLOW_THRESHOLD_MS = 3000L
    }
}
