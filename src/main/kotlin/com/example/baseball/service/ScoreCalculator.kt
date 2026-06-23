package com.example.baseball.service

import com.example.baseball.domain.game.GameDifficulty
import kotlin.math.max

/**
 * 승리 시 획득 점수를 계산하는 순수 함수.
 *
 * 설계 의도 (PLAN 3-1 / 6-B):
 * - 숫자야구는 상대 없는 싱글플레이 + 매월 0 리셋 시즌제 → "패배 감점"이 구조적으로 없다.
 *   따라서 MMR(−)이 아니라 누적 score(+ 위주) 모델을 쓴다. 이길 때는 항상 양수를 획득하되,
 *   적게 틀릴수록(=tries가 작을수록) 더 많이 받는다. 누적 점수는 이 계산기로 깎이지 않는다.
 * - 상수(BASE/STEP/MIN_GAIN)를 한곳에 분리 → 배포 없이 밸런싱을 조정한다.
 * - 스프링/DB/카카오와 무관한 순수 함수 → 단위 테스트로 빠르게 검증한다(BaseballJudge와 동일 전략).
 *
 * gain = max(MIN_GAIN, BASE - tries * STEP) * difficulty.multiplier
 */
object ScoreCalculator {

    /** 기본 점수: 1번에 맞혔을 때 근사 상한. */
    const val BASE = 100

    /** 시도 1회당 차감폭: 적게 틀릴수록 더 받게 만든다. */
    const val STEP = 5

    /** 최소 보장(배수 적용 전): 아무리 많이 틀려도 이 아래로는 안 내려간다. */
    const val MIN_GAIN = 20

    /**
     * 승리 시 획득 점수를 계산한다.
     *
     * @param tries      정답까지의 총 시도 횟수(정답 추측 포함). 1 이상이어야 한다.
     * @param difficulty 난이도. 배수(EASY 0.5 / NORMAL 1.0 / HARD 2.0)를 곱한다.
     * @return 획득 점수(항상 1 이상). 시도가 많아도 MIN_GAIN(×배수)만큼은 보장한다.
     * @throws IllegalArgumentException tries가 1 미만일 때(승리는 최소 1회 시도가 필요).
     */
    fun gain(tries: Int, difficulty: GameDifficulty = GameDifficulty.NORMAL): Int {
        require(tries >= 1) { "tries는 1 이상이어야 합니다. (입력: $tries)" }
        val raw = max(MIN_GAIN, BASE - tries * STEP)
        return (raw * difficulty.multiplier).toInt()
    }
}
