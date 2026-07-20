package com.example.baseball.controller

import com.example.baseball.dto.SkillRequest
import com.example.baseball.dto.SkillResponse
import com.example.baseball.service.GameService
import com.example.baseball.service.GuessOutcome
import com.example.baseball.service.Percentile
import com.example.baseball.service.RankTitle
import com.example.baseball.service.RankingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "숫자야구 스킬", description = "카카오 오픈빌더 스킬 엔드포인트")
@RestController
class SkillController(
    private val gameService: GameService,
    private val rankingService: RankingService,
    // 썸네일 이미지 베이스 URL. 비어 있으면(로컬/테스트 기본값) BasicCard 대신 simpleText로 폴백한다.
    // prod에서만 실제 URL(https://numbers-baseball.com/images)을 주입해 카드로 노출한다.
    @Value("\${kakao.image-base-url:}") private val imageBaseUrl: String,
    // 멘션 프리필 버튼 라벨. 환경별로 다르게 노출한다(dev: 테스트용 문구, prod: 안내 문구).
    @Value("\${kakao.mention-button-label:제출}") private val mentionButtonLabel: String,
) {
    /** 결과 상태별 썸네일 파일명(확장자 포함). static/images/ 아래 실제 파일명과 일치해야 한다. */
    private enum class ResultImage(val file: String) {
        START("start.png"),
        STRIKE("strike.png"), // 스트라이크 1개 이상
        BALL("ball.png"),     // 스트라이크 0 + 볼 1개 이상
        OUT("out.png"),       // 0S 0B
        ANSWER("answer.png"), // 정답(승리)
    }

    /** 카드(BasicCard/TextCard) 노출 여부. 이미지 URL이 설정된 환경(prod/local)에서만 카드를 쓴다. */
    private val cardsEnabled: Boolean get() = imageBaseUrl.isNotBlank()

    /** 멘션 프리필이 심는 제로폭 문자군(U+200B/C/D, U+FEFF). 발화 판정 전에 제거한다. */
    private val zeroWidthChars = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")
    /**
     * 카카오 오픈빌더 스킬 엔드포인트.
     * 카카오 5초 타임아웃 안에 끝나야 하므로 무거운 작업은 두지 않는다.
     */
    @Operation(
        summary = "발화 처리",
        description = "utterance 에 '시작'/'새게임' → 새 게임, 서로 다른 숫자 → 추측, '포기' → 정답 공개. 그 외엔 도움말.",
    )
    @PostMapping("/skill/play")
    fun play(@RequestBody request: SkillRequest): SkillResponse {
        val userId = request.userRequest.user.id
        val botKey = request.userRequest.chat?.properties?.botGroupKey
        // 멘션 프리필 버튼이 심는 제로폭 공백(U+200B 등)을 제거한 뒤 판정한다.
        // 이게 남으면 "​1234"가 숫자 판정(all isDigit)을 통과하지 못해 추측이 먹히지 않는다.
        val utterance = request.userRequest.utterance.replace(zeroWidthChars, "").trim()
        return handle(userId, botKey, utterance)
    }

    /**
     * utterance를 명령으로 분기한다.
     * 입력 규칙 위반·진행중 게임 없음 등은 예외로 던지고, SkillExceptionHandler 가 안내 메시지로 변환한다.
     * (예외를 여기서 삼키지 않아야 LogTraceAspect 가 실패를 관측할 수 있다 — 9-F 증상 4)
     *
     * 카드 전환 범위: START(시작)·GUESS(추측)은 BasicCard(썸네일), GIVEUP(포기)은 TextCard(썸네일 없음), RANKING/RULES/HELP은 simpleText.
     */
    private fun handle(userId: String, botKey: String?, utterance: String): SkillResponse =
        when (SkillCommand.classify(utterance)) {
            SkillCommand.START -> {
                gameService.startGame(userId, botKey)
                val text = "새 게임을 시작했습니다. ${GameService.DIGITS}자리 숫자를 맞혀보세요. (예: 1234)"
                cardOrText(
                    image = ResultImage.START,
                    title = "⚾ 새 게임 시작",
                    description = text,
                    buttons = listOf(
                        SkillResponse.Button.mentionPrefill(mentionButtonLabel),
                        SkillResponse.Button.message("포기", "포기"),
                    ),
                    fallbackText = text,
                )
            }

            SkillCommand.GIVEUP -> {
                val answer = gameService.giveUp(userId)
                // 포기는 썸네일 없이 버튼만 필요 → 이미지 없는 textCard 사용(패배 이미지 미준비).
                textCardOrText(
                    title = "🏳️ 게임 포기",
                    description = "정답은 $answer 였습니다. 다시 도전해보세요!",
                    buttons = listOf(
                        SkillResponse.Button.message("게임 규칙", "게임 규칙"),
                        SkillResponse.Button.message("시작", "시작"),
                    ),
                    fallbackText = "게임을 포기했습니다. 정답은 $answer 였습니다. '시작'으로 다시 도전하세요.",
                )
            }

            SkillCommand.RANKING -> formatRanking(botKey)

            SkillCommand.GUESS -> formatGuess(gameService.guess(userId, botKey, utterance))

            SkillCommand.RULES -> {
                val body = rulesBody()
                textCardOrText(
                    title = "⚾ 숫자야구 규칙",
                    description = body,
                    buttons = listOf(SkillResponse.Button.message("시작", "시작")),
                    fallbackText = "[숫자야구 규칙]\n$body",
                )
            }

            SkillCommand.HELP -> SkillResponse.text(helpMessage())
        }

    /**
     * 추측 결과를 카드/텍스트로 변환한다.
     * - 승리: answer 썸네일 + [시작, 랭킹]
     * - 진행중: 아웃/스트라이크/볼 썸네일 + [제출](오픈채팅 멘션 프리필용)
     * 폴백 문구는 기존 simpleText와 동일하게 유지(하위 호환·테스트 안정).
     */
    private fun formatGuess(outcome: GuessOutcome): SkillResponse {
        val r = outcome.result
        if (r.isWin) {
            val before = outcome.totalScore - outcome.gain   // 적립 전 누적 점수
            val headline = winHeadline(outcome.tries)
            val body = buildString {
                appendLine("정답 ${outcome.answer}, 맞혔어요! 🎉")
                appendLine("+${outcome.gain}점 ($before → ${outcome.totalScore})")
                // 표본이 충분할 때만(percentile != null) 상위% 줄을 노출한다(적으면 무의미해 생략).
                outcome.percentile?.let { appendLine(percentileLine(it)) }
                appendLine()
                append("'시작'으로 다시 도전하세요!")
            }
            return cardOrText(
                image = ResultImage.ANSWER,
                title = headline,
                description = body,
                buttons = listOf(
                    SkillResponse.Button.message("시작", "시작"),
                    SkillResponse.Button.message("랭킹", "랭킹"),
                ),
                fallbackText = "$headline\n$body",
            )
        }
        // 진행중: 아웃(0S0B) → 스트라이크(1개+) → 볼 순으로 썸네일을 고른다.
        val (image, sb) = when {
            r.isOut -> ResultImage.OUT to "OUT (0S 0B)"
            r.strike > 0 -> ResultImage.STRIKE to "${r.strike}S ${r.ball}B"
            else -> ResultImage.BALL to "0S ${r.ball}B"
        }
        return cardOrText(
            image = image,
            title = sb,
            description = "${outcome.tries}번째 시도예요. '제출'을 눌러 다음 숫자를 입력하세요. (예: 7428)",
            // 오픈채팅에선 message 버튼 클릭 시 "@봇 "이 입력창에 프리필된다(즉시 전송 아님).
            // messageText를 빈 값으로 두어 멘션만 깔끔히 채워지도록 한다(실제 프리필 내용은 오픈채팅 테스트로 확인).
            buttons = listOf(SkillResponse.Button.mentionPrefill(mentionButtonLabel)),
            // fallbackText는 기존 simpleText 문구("N번째 시도: ...")를 유지해 하위 호환/테스트 안정.
            fallbackText = "${outcome.tries}번째 시도: $sb",
        )
    }

    /**
     * 이미지 URL이 설정돼 있으면 BasicCard, 아니면 simpleText로 폴백한다(썸네일 필수라 미준비 시 응답 누락 방지).
     * title/description은 스펙 상한(50/230자) 내로 잘라 생성 시점 예외를 피한다.
     */
    private fun cardOrText(
        image: ResultImage,
        title: String,
        description: String,
        buttons: List<SkillResponse.Button>,
        fallbackText: String,
    ): SkillResponse {
        if (!cardsEnabled) return SkillResponse.text(fallbackText)
        val card = SkillResponse.BasicCard(
            thumbnail = SkillResponse.Thumbnail(
                imageUrl = "${imageBaseUrl.trimEnd('/')}/${image.file}",
                altText = title.take(SkillResponse.BasicCard.TITLE_MAX),
                fixedRatio = true, // 이미지가 1:1(800×800)이라 크롭 방지 + 버튼 가로 최대 2개
            ),
            title = title.take(SkillResponse.BasicCard.TITLE_MAX),
            description = description.take(SkillResponse.BasicCard.DESC_MAX),
            buttons = buttons.ifEmpty { null },
        )
        return SkillResponse.card(card)
    }

    /**
     * 썸네일 없는 TextCard(버튼 카드)로 변환한다. 카드 비활성(테스트/기본)이면 simpleText 폴백.
     * title/description은 스펙 상한(50/400자) 내로 잘라 생성 시점 예외를 피한다.
     */
    private fun textCardOrText(
        title: String,
        description: String,
        buttons: List<SkillResponse.Button>,
        fallbackText: String,
    ): SkillResponse {
        if (!cardsEnabled) return SkillResponse.text(fallbackText)
        val card = SkillResponse.TextCard(
            title = title.take(SkillResponse.BasicCard.TITLE_MAX),
            description = description.take(SkillResponse.TextCard.DESC_MAX),
            buttons = buttons.ifEmpty { null },
        )
        return SkillResponse.textCard(card)
    }

    /**
     * 정답 축하 연출(STEP-11). 시도 횟수가 적을수록 임팩트가 큰 문구를 준다(성취감·리텐션).
     * 모든 분기에 "정답" 키워드를 유지해, 승리 판정 여부를 문구로 확인하는 상위 로직/테스트가 안정적으로 매칭된다.
     */
    private fun winHeadline(tries: Int): String = when (tries) {
        1 -> "🎯 딱 한 번에 정답! 초구 만루홈런이에요! ⚾"
        in 2..3 -> "🎉 정답! ${tries}번 만에 시원한 홈런을 날렸어요! ⚾"
        else -> "🎉 정답! ${tries}번 만에 맞혔어요! ⚾"
    }

    /**
     * 상위 백분위 노출(STEP-11). 상위 30% 이내면 구간별 칭호(RankTitle)를, 그 밖이면 기본 메달 문구를 앞에 붙인다.
     * 백분위는 이미 계산된 Percentile 값을 재사용하고, 칭호는 topPercent 매핑만 얹는다(중복 계산 없음).
     */
    private fun percentileLine(p: Percentile): String {
        val badge = RankTitle.of(p.topPercent)?.let { "${it.emoji} ${it.label} · " } ?: "🏅 "
        return "${badge}\n이번 시즌 상위 ${p.topPercent}% (${p.rank}위 / ${p.total}명)"
    }

    /**
     * 봇(채팅방) 랭킹 TOP 10. botKey 없음·빈 랭킹은 안내 메시지로 변환.
     * 각 줄의 사용자 이름은 "{{#mentions.userN}}" 자리표시자로 두고, extra.mentions 에 botUserKey 를 등록해
     * 카카오가 실제 닉네임(@사용자) 멘션으로 치환하게 한다(STEP 12 배포 피드백 — 원시 키 노출 제거).
     */
    private fun formatRanking(botKey: String?): SkillResponse {
        if (botKey == null) return SkillResponse.text("채팅방 정보를 확인할 수 없어 랭킹을 보여줄 수 없습니다.")

        val ranking = rankingService.getBotRanking(botKey)
        if (ranking.isEmpty()) {
            return SkillResponse.text("아직 랭킹에 등록된 점수가 없습니다. 게임에서 정답을 맞히면 점수가 쌓여요.")
        }

        val mentions = LinkedHashMap<String, SkillResponse.Mention>()
        val text = buildString {
            appendLine("🏆 이 채팅방 랭킹 TOP ${ranking.size}")
            ranking.forEach { e ->
                val key = "user${e.rank}" // 텍스트 자리표시자 키와 mentions 맵 키가 대응해야 한다.
                mentions[key] = SkillResponse.Mention(type = "botUserKey", id = e.botUserKey)
                // 상위 30/20/10% 구간이면 RankTitle 이모지로 강조(구간 밖·표본 부족이면 빈 문자열).
                val badge = e.title?.let { "${it.emoji} " } ?: ""
                appendLine("${e.rank}위  $badge{{#mentions.$key}}  ${e.score}점")
            }
        }.trimEnd()
        return SkillResponse.textWithMentions(text, mentions)
    }

    /** 사용법: 명령어 안내. 알 수 없는 발화(HELP fallback)도 이 메시지로 안내한다. */
    private fun helpMessage(): String =
        """
        숫자야구 사용법입니다.
        - '시작' : 새 게임 시작
        - 서로 다른 숫자 입력 : 추측 (예: 1234)
        - '포기' : 정답 공개
        - '랭킹' : 이 채팅방 점수 순위
        - '게임 규칙' : 게임 방법 설명
        """.trimIndent()

    /**
     * 게임 규칙 본문: 승패 판정(STRIKE/BALL/OUT) 설명. 자릿수는 GameService.DIGITS로 단일화.
     * BaseballJudge 판정과 문구를 일치시킨다: STRIKE=자리+숫자, BALL=숫자만(자리 다름), OUT=0S 0B.
     * 예시 숫자는 이해를 돕기 위한 고정 값(DIGITS=4 기준)이다.
     * 헤더("[숫자야구 규칙]")는 카드에선 title로 분리되므로 본문에는 넣지 않는다(simpleText 폴백에서만 앞에 붙인다).
     */
    private fun rulesBody(): String {
        val n = GameService.DIGITS
        return """
            서로 다른 ${n}자리 숫자(중복 없이)를 맞히는 게임입니다.
            - STRIKE : 숫자와 자리 모두 일치
            - BALL : 숫자는 있지만 자리가 다름
            - OUT : 맞는 숫자가 하나도 없음 (0S 0B)

            예) 정답 1234 / 추측 1325 → 1S 2B
            ${n}S가 되면 승리!
            '시작'으로 도전하세요.
        """.trimIndent()
    }
}
