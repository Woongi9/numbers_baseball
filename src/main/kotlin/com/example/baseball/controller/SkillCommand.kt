package com.example.baseball.controller

/**
 * 사용자 발화의 의도(명령) 분류.
 *
 * 컨트롤러의 분기와 로그의 intent 표기가 **같은 규칙**을 쓰도록 분류 로직을 한곳에 둔다.
 * (분기 단어가 컨트롤러와 aspect 두 곳에 중복되면 규칙이 어긋날 수 있어 단일 출처로 통합)
 */
enum class SkillCommand {
    START, GIVEUP, RANKING, GUESS, RULES, HELP;

    companion object {
        private val START_WORDS = setOf("시작", "새게임", "시작하기")
        private const val GIVEUP_WORD = "포기"
        private val RANKING_WORDS = setOf("랭킹", "봇랭킹", "순위")
        private val RULES_WORDS = setOf("게임규칙", "게임 규칙", "규칙")
        private val HELP_WORDS = setOf("사용법", "도움말")

        /**
         * 발화를 명령으로 분류한다.
         * 명령어(시작/포기/랭킹/규칙/사용법) → 숫자(추측) → 그 외(사용법) 순으로 판정한다.
         * 명령어는 숫자가 아니므로 GUESS 판정과 겹치지 않는다.
         * RULES(게임 규칙)와 HELP(사용법)는 역할이 다르다: RULES는 승패 규칙 설명, HELP는 명령어 사용법.
         * HELP_WORDS 매핑은 fallback과 결과가 같지만, "사용법"이 의도된 명령임을 코드로 드러내려 명시한다.
         */
        fun classify(utterance: String): SkillCommand {
            val u = utterance.trim()
            return when {
                u in START_WORDS -> START
                u == GIVEUP_WORD -> GIVEUP
                u in RANKING_WORDS -> RANKING
                u in RULES_WORDS -> RULES
                u in HELP_WORDS -> HELP
                u.isNotBlank() && u.all { it.isDigit() } -> GUESS
                else -> HELP
            }
        }
    }
}
