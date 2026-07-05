package com.example.baseball.service

/**
 * 상위 백분위 → 칭호/이모지 매핑 (STEP-11 표현 레이어).
 *
 * 설계 의도:
 * - 성취감·리텐션용 UX. topPercent(작을수록 상위)만으로 결정되는 순수 매핑이라
 *   PercentileCalculator 결과(Percentile.topPercent)를 그대로 재사용한다(중복 계산 없음).
 * - 구간 임계값(maxPercent)을 열거 상수로 분리 → 배포 없이 칭호/구간 밸런싱을 조정한다
 *   (BaseballJudge·ScoreCalculator·PercentileCalculator 와 동일한 "순수 + 상수 분리 + 단위 테스트" 전략).
 * - 경계는 "상위 X% 이내"면 해당 칭호(포함). 10 → TOP_10, 20 → TOP_20, 30 → TOP_30, 그 초과는 칭호 없음(null).
 *
 * @property emoji      노출용 이모지.
 * @property label      노출용 칭호 문구.
 * @property maxPercent 이 칭호가 적용되는 상위 백분위 상한(이내이면 부여).
 */
enum class RankTitle(val emoji: String, val label: String, val maxPercent: Int) {
    // 선언 순서 = maxPercent 오름차순. of() 가 위에서부터 첫 매칭을 취하므로 이 순서가 곧 우선순위다.
    TOP_10("🥇", "전설의 4번타자", 10),
    TOP_20("🥈", "리그 대표 타자", 20),
    TOP_30("🥉", "떠오르는 유망주", 30);

    companion object {
        /**
         * 상위 백분위에 해당하는 칭호를 반환한다.
         *
         * @param topPercent 상위 백분위(1~100, 작을수록 상위). PercentileCalculator 산출값을 그대로 넣는다.
         * @return 해당 구간 칭호. 상위 30% 밖(topPercent > 30)이면 null.
         * @throws IllegalArgumentException topPercent 가 1 미만일 때(백분위는 1 이상).
         */
        fun of(topPercent: Int): RankTitle? {
            require(topPercent >= 1) { "topPercent 는 1 이상이어야 합니다. (입력: $topPercent)" }
            return RankTitle.entries.firstOrNull { topPercent <= it.maxPercent }
        }
    }
}
