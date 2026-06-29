package com.example.baseball.common

/**
 * 로깅 상관관계(MDC) 키 모음. 요청 1건의 모든 로그를 같은 traceId 로 묶기 위해
 * aspect(키 적재)와 서비스(키 조회)가 같은 상수를 공유한다(매직 스트링·드리프트 방지).
 */
object TraceKeys {
    const val TRACE_ID = "traceId"
    const val BOT_KEY = "botKey"
    const val BOT_USER = "botUserKey"
}
