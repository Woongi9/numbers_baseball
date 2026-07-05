package com.example.baseball.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 카카오 오픈빌더 스킬 응답 (version 2.0).
 *
 * outputs[] 안에 simpleText 또는 basicCard 중 하나가 담긴다.
 * - simpleText 응답:
 *   { "version": "2.0", "template": { "outputs": [ { "simpleText": { "text": "..." } } ] } }
 * - basicCard 응답(썸네일 + title/description + buttons):
 *   { "version": "2.0", "template": { "outputs": [ { "basicCard": { ... } } ] } }
 *
 * NON_NULL: 사용하지 않는 필드(예: simpleText 응답의 basicCard)를 직렬화에서 제외해야
 * 카카오가 `"basicCard": null` 같은 값을 스펙 위반으로 거부하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillResponse(
    val version: String,
    val template: Template,
) {
    data class Template(
        val outputs: List<Output>,
    )

    /** outputs 원소. simpleText / basicCard / textCard 중 하나만 채운다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Output(
        val simpleText: SimpleText? = null,
        val basicCard: BasicCard? = null,
        val textCard: TextCard? = null,
    )

    data class SimpleText(
        val text: String,
    )

    /**
     * 텍스트 카드. 썸네일 없이 title/description + 버튼만 필요할 때 사용(예: 포기 응답).
     * title/description 중 최소 하나 필수. 단일형은 합쳐 최대 400자.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class TextCard(
        val title: String? = null,
        val description: String? = null,
        val buttons: List<Button>? = null,
    ) {
        init {
            require(title != null || description != null) { "title/description 중 최소 하나는 필요합니다." }
            title?.let { require(it.length <= BasicCard.TITLE_MAX) { "title은 ${BasicCard.TITLE_MAX}자 이하여야 합니다." } }
            description?.let { require(it.length <= DESC_MAX) { "description은 ${DESC_MAX}자 이하여야 합니다." } }
            buttons?.let { require(it.size <= BasicCard.BUTTON_MAX) { "buttons는 최대 ${BasicCard.BUTTON_MAX}개입니다." } }
        }

        companion object {
            const val DESC_MAX = 400 // 단일형 title+description 합산 상한(간단히 description 단독 상한으로 검증)
        }
    }

    /**
     * 카카오 BasicCard. 썸네일은 필수, title/description/buttons는 선택.
     * 규격 위반을 "생성 시점"에 차단한다(fail-fast) — 잘못된 카드가 카카오로 나가 렌더 실패하는 것보다,
     * 서버에서 먼저 터뜨려 테스트·로그로 관측하는 편이 안전하다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class BasicCard(
        val thumbnail: Thumbnail,
        val title: String? = null,
        val description: String? = null,
        val buttons: List<Button>? = null,
    ) {
        init {
            title?.let {
                require(it.length <= TITLE_MAX) { "title은 ${TITLE_MAX}자 이하여야 합니다(현재 ${it.length})." }
            }
            description?.let {
                require(it.length <= DESC_MAX) { "description은 ${DESC_MAX}자 이하여야 합니다(현재 ${it.length})." }
            }
            buttons?.let {
                require(it.size <= BUTTON_MAX) { "buttons는 최대 ${BUTTON_MAX}개입니다(현재 ${it.size})." }
            }
        }

        companion object {
            const val TITLE_MAX = 50
            const val DESC_MAX = 230
            const val BUTTON_MAX = 3 // 세로 레이아웃 기준 최대 3개
        }
    }

    /**
     * 썸네일. fixedRatio=true 면 이미지를 1:1 비율로 원본 유지(크롭 없음)하고 버튼은 가로 최대 2개.
     * false(기본)면 2:1로 중앙 크롭 → 정사각 이미지는 위아래가 잘리므로, 1:1 이미지에는 true를 쓴다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Thumbnail(
        val imageUrl: String,
        val altText: String? = null,
        val fixedRatio: Boolean? = null,
    )

    /**
     * 카드 버튼. action에 따라 필요한 필드가 다르다.
     * - "message" : messageText (해당 문구를 사용자가 입력한 것처럼 재발화)
     * - "block"   : blockId    (특정 블록 호출)
     * - "webLink" : webLinkUrl
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Button(
        val label: String,
        val action: String,
        val messageText: String? = null,
        val blockId: String? = null,
        val webLinkUrl: String? = null,
    ) {
        companion object {
            /** '메시지' 버튼: 누르면 messageText를 사용자가 입력한 것처럼 재발화한다. */
            fun message(label: String, messageText: String): Button =
                Button(label = label, action = "message", messageText = messageText)

            /**
             * 오픈채팅 멘션 프리필용 버튼.
             * 오픈채팅에서 message 버튼은 즉시 전송이 아니라 입력창에 "@봇 "을 프리필한다.
             * messageText를 빈 문자열로 두어 멘션만 채워지게 하고, 유저가 값을 이어서 입력한다.
             * (예: 숫자야구의 다음 추측 입력)
             */
            fun mentionPrefill(label: String): Button =
                Button(label = label, action = "message", messageText = "")
        }
    }

    companion object {
        /** 단순 텍스트 응답 한 개를 만드는 팩토리. */
        fun text(message: String): SkillResponse =
            SkillResponse(
                version = "2.0",
                template = Template(listOf(Output(simpleText = SimpleText(message)))),
            )

        /** BasicCard 응답 한 개를 만드는 팩토리. */
        fun card(card: BasicCard): SkillResponse =
            SkillResponse(
                version = "2.0",
                template = Template(listOf(Output(basicCard = card))),
            )

        /** TextCard(썸네일 없는 버튼 카드) 응답 한 개를 만드는 팩토리. */
        fun textCard(card: TextCard): SkillResponse =
            SkillResponse(
                version = "2.0",
                template = Template(listOf(Output(textCard = card))),
            )
    }
}
