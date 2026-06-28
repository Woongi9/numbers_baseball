package com.example.baseball.service

import kotlin.math.ceil

/**
 * 승리 응답에 보여줄 "상위 N%" 정보. 표본이 충분할 때만 생성된다.
 *
 * @property rank       내 등수(1부터). 동점자는 같은 등수로 묶인다(공동순위).
 * @property total      전체 참가자 수.
 * @property topPercent 상위 백분위(작을수록 상위권). 1~100.
 */
data class Percentile(
    val rank: Int,
    val total: Int,
    val topPercent: Int,
)

/**
 * 상위 백분위 계산 순수 함수.
 *
 * 설계 의도 (PLAN 9-P):
 * - 정렬/전체 조회 없이 "나보다 점수 높은 사람 수(higher)"와 "전체 수(total)" 두 카운트만으로 끝난다.
 *   → 등수 = higher + 1 (동점은 strictly-greater 라 공동순위로 묶임).
 * - 표본이 적으면(혼자=항상 100%) 백분위가 무의미하므로 null 로 "표본 부족"을 표현한다.
 * - DB/스프링과 무관한 순수 함수 → 경계값(1등·동점·꼴찌·표본부족)을 단위 테스트로 검증한다
 *   (BaseballJudge·ScoreCalculator 와 동일 전략).
 *
 * topPercent = ceil(rank * 100 / total)
 */
object PercentileCalculator {

    /**
     * 백분위를 보여줄 최소 표본 수. 이 미만이면 표본 부족으로 보고 null.
     * 너무 적은 인원에서 "상위 100%" 같은 오해를 막는다. (배포 없이 조정 가능하도록 상수로 분리)
     */
    const val MIN_SAMPLE = 5

    /**
     * @param higher 나보다 점수가 '엄격히 높은' 사람 수(동점 제외).
     * @param total  전체 참가자 수(나 포함).
     * @return 백분위 정보. 표본 부족(total < MIN_SAMPLE)이면 null.
     * @throws IllegalArgumentException 인자가 음수이거나 higher가 total을 넘을 때(불가능한 입력).
     */
    fun of(higher: Int, total: Int): Percentile? {
        require(higher >= 0) { "higher 는 0 이상이어야 합니다. (입력: $higher)" }
        require(total >= 0) { "total 은 0 이상이어야 합니다. (입력: $total)" }
        require(higher < total || total == 0) { "higher($higher) 는 total($total) 보다 작아야 합니다." }

        if (total < MIN_SAMPLE) return null

        val rank = higher + 1
        val topPercent = ceil(rank * 100.0 / total).toInt()
        return Percentile(rank = rank, total = total, topPercent = topPercent)
    }
}
