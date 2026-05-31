package com.example.baseball.dto

/**
 * 카카오 오픈빌더 스킬 응답 (version 2.0, simpleText 한 개).
 * 직렬화하면 아래 형태가 된다.
 * { "version": "2.0", "template": { "outputs": [ { "simpleText": { "text": "..." } } ] } }
 */
data class SkillResponse(
    val version: String,
    val template: Template,
) {
    data class Template(
        val outputs: List<Output>,
    )

    data class Output(
        val simpleText: SimpleText,
    )

    data class SimpleText(
        val text: String,
    )

    companion object {
        /** 단순 텍스트 응답 한 개를 만드는 팩토리. */
        fun text(message: String): SkillResponse =
            SkillResponse(
                version = "2.0",
                template = Template(listOf(Output(SimpleText(message)))),
            )
    }
}
