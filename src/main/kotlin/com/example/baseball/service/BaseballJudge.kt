package com.example.baseball.service

/**
 * 판정 결과. strike/ball 외 편의 속성을 함께 제공한다.
 * digits(자릿수)를 들고 있어야 4S가 아니라 "전부 스트라이크 = 승리"를 정확히 판단할 수 있다.
 */
data class JudgeResult(
    val strike: Int,
    val ball: Int,
    val isWin: Boolean,
) {
    val isOut: Boolean get() = strike == 0 && ball == 0
}

/**
 * 숫자야구 판정 로직 (순수 함수).
 * 스프링 / DB / 카카오와 분리되어 단위 테스트로 단독 검증 가능하다.
 */
object BaseballJudge {

    /**
     * @param answer 정답 (시스템 생성, 서로 다른 숫자)
     * @param guess  사용자 추측
     * @return 스트라이크/볼 판정 결과
     * @throws IllegalArgumentException 자릿수 불일치 / 숫자 외 문자 / 중복 숫자
     */
    fun judge(answer: String, guess: String): JudgeResult {
        validate(answer, guess)

        var strike = 0
        var ball = 0
        for (i in guess.indices) {
            when {
                guess[i] == answer[i] -> strike++          // 자리+숫자 일치
                answer.contains(guess[i]) -> ball++         // 숫자만 일치(자리 다름)
                // 둘 다 아니면 아웃 → 카운트 없음
            }
        }
        return JudgeResult(strike = strike, ball = ball, isWin = strike == answer.length)
    }

    private fun validate(answer: String, guess: String) {
        require(guess.length == answer.length) {
            "자릿수가 올바르지 않습니다. ${answer.length}자리 숫자를 입력하세요."
        }
        require(guess.all { it.isDigit() }) {
            "숫자만 입력할 수 있습니다."
        }
        require(guess.toSet().size == guess.length) {
            "서로 다른 숫자를 입력해야 합니다. (중복 불가)"
        }
    }
}
