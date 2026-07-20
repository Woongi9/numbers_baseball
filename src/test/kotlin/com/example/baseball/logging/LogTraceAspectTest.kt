package com.example.baseball.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.baseball.dto.SkillRequest
import com.example.baseball.dto.SkillResponse
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@DisplayName("LogTraceAspect - 요청 로깅 래퍼")
class LogTraceAspectTest {

    private val aspect = LogTraceAspect()

    private val request = SkillRequest(
        userRequest = SkillRequest.UserRequest(
            utterance = "1234",
            user = SkillRequest.User(id = "u1"),
            chat = SkillRequest.Chat(
                properties = SkillRequest.Properties(botGroupKey = "bot-1"),
            ),
        ),
    )

    /** proceed() 가 주어진 동작을 하도록 만든 ProceedingJoinPoint mock. */
    private fun joinPoint(behavior: () -> Any?): ProceedingJoinPoint {
        val jp = mockk<ProceedingJoinPoint>()
        every { jp.args } returns arrayOf<Any?>(request)
        every { jp.proceed() } answers { behavior() }
        return jp
    }

    @Test
    @DisplayName("정상 처리 시 proceed 결과를 그대로 반환한다")
    fun passesThroughResult() {
        val response = SkillResponse.text("정답입니다!")
        val outcome = aspect.trace(joinPoint { response })
        assertSame(response, outcome)
    }

    @Test
    @DisplayName("예외를 삼키지 않고 그대로 재던진다 (실패 관측 보장 — 9-F 증상 4)")
    fun rethrowsException() {
        val jp = joinPoint { throw IllegalStateException("진행 중인 게임이 없습니다.") }
        assertThrows(IllegalStateException::class.java) { aspect.trace(jp) }
    }

    /** "SkillTrace" 로거에 ListAppender 를 붙여 phase=END 한 줄을 캡처한다. */
    private fun captureEndLine(behavior: () -> Any?): ILoggingEvent {
        val logger = LoggerFactory.getLogger("SkillTrace") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runCatching { aspect.trace(joinPoint(behavior)) }
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.first { it.formattedMessage.contains("phase=END") }
    }

    @Test
    @DisplayName("예상된 입력/상태 예외는 REJECTED 로 남겨 error_count 에 안 잡힌다")
    fun expectedExceptionsAreRejectedNotError() {
        val end = captureEndLine { throw IllegalArgumentException("자릿수가 올바르지 않습니다.") }
        assertTrue(end.formattedMessage.contains("status=REJECTED"), end.formattedMessage)
        assertTrue(end.level != Level.ERROR, "예상된 예외는 ERROR 레벨이면 안 됨")
    }

    @Test
    @DisplayName("예상 못한 예외만 status=ERROR 로 남겨 알람 대상이 된다")
    fun unexpectedExceptionsAreError() {
        val end = captureEndLine { throw RuntimeException("DB down") }
        assertTrue(end.formattedMessage.contains("status=ERROR"), end.formattedMessage)
        assertTrue(end.level == Level.ERROR, end.level.toString())
    }

    @Test
    @DisplayName("처리 후 MDC 를 정리해 스레드에 흔적을 남기지 않는다")
    fun clearsMdcAfterHandling() {
        aspect.trace(joinPoint { SkillResponse.text("ok") })
        assertNull(MDC.get("traceId"))

        // 예외 경로에서도 정리되는지 확인
        runCatching { aspect.trace(joinPoint { throw IllegalArgumentException("bad") }) }
        assertNull(MDC.get("traceId"))
    }
}
