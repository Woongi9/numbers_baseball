package com.example.baseball.controller

/**
 * 사용자 발화의 의도(명령) 분류.
 *
 * 컨트롤러의 분기와 로그의 intent 표기가 **같은 규칙**을 쓰도록 분류 로직을 한곳에 둔다.
 * (분기 단어가 컨트롤러와 aspect 두 곳에 중복되면 규칙이 어긋날 수 있어 단일 출처로 통합)
 */
enum class SkillCommand {
    START, GIVEUP, RANKING, GUESS, HELP;

    companion object {
        private val START_WORDS = setOf("시작", "새게임", "시작하기")
        private const val GIVEUP_WORD = "포기"
        private val RANKING_WORDS = setOf("랭킹", "봇랭킹", "순위")
        // 규칙(게임규칙/규칙)과 사용법을 한 "게임 규칙" 응답으로 통합했다(6-E ③).
        // "도움말"은 카카오 기본 블록(예약 발화)이라 여기 넣지 않는다 — 우리 스킬로 들어오지 않는다.
        private val HELP_WORDS = setOf("게임규칙", "게임 규칙", "규칙", "사용법")

        /**
         * 발화를 명령으로 분류한다.
         * 명령어(시작/포기/랭킹/게임 규칙) → 숫자(추측) → 그 외(게임 규칙) 순으로 판정한다.
         * 명령어는 숫자가 아니므로 GUESS 판정과 겹치지 않는다.
         * HELP는 규칙 설명 + 명령어 안내를 겸한다(6-E ③ 통합). 미인식 발화도 HELP로 안내한다.
         */
        fun classify(utterance: String): SkillCommand {
            val u = utterance.trim()
            return when {
                u in START_WORDS -> START
                u == GIVEUP_WORD -> GIVEUP
                u in RANKING_WORDS -> RANKING
                u in HELP_WORDS -> HELP
                u.isNotBlank() && u.all { it.isDigit() } -> GUESS
                else -> HELP
            }
        }
    }
}
