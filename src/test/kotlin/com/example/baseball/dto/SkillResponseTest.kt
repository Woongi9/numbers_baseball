package com.example.baseball.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("SkillResponse 직렬화 - 카카오 스킬 JSON 스펙 일치")
class SkillResponseTest {

    private val mapper = jacksonObjectMapper()

    @Nested
    @DisplayName("직렬화 형태")
    inner class Serialization {

        @Test
        @DisplayName("simpleText 응답은 basicCard 키를 포함하지 않는다(NON_NULL)")
        fun simpleTextOmitsBasicCard() {
            val json = mapper.readTree(mapper.writeValueAsString(SkillResponse.text("안녕")))
            val output = json["template"]["outputs"][0]

            assertEquals("2.0", json["version"].asText())
            assertEquals("안녕", output["simpleText"]["text"].asText())
            assertFalse(output.has("basicCard")) // null 필드는 직렬화 제외
        }

        @Test
        @DisplayName("basicCard 응답은 thumbnail/buttons를 담고 simpleText 키는 없다")
        fun basicCardShape() {
            val card = SkillResponse.BasicCard(
                thumbnail = SkillResponse.Thumbnail("https://numbers-baseball.com/images/answer.png", "정답"),
                title = "⚾ 4S 2B",
                description = "스트라이크 4, 볼 2!",
                buttons = listOf(SkillResponse.Button.message("다시 도전", "시작")),
            )
            val output = mapper.readTree(mapper.writeValueAsString(SkillResponse.card(card)))["template"]["outputs"][0]
            val basicCard = output["basicCard"]

            assertFalse(output.has("simpleText"))
            assertEquals(
                "https://numbers-baseball.com/images/answer.png",
                basicCard["thumbnail"]["imageUrl"].asText(),
            )
            assertEquals("⚾ 4S 2B", basicCard["title"].asText())
            assertEquals(1, basicCard["buttons"].size())
            assertEquals("message", basicCard["buttons"][0]["action"].asText())
            assertEquals("시작", basicCard["buttons"][0]["messageText"].asText())
        }

        @Test
        @DisplayName("Button.message는 사용하지 않는 blockId/webLinkUrl을 직렬화하지 않는다")
        fun buttonOmitsUnusedFields() {
            val json = mapper.readTree(mapper.writeValueAsString(SkillResponse.Button.message("포기", "포기")))
            assertTrue(json.has("messageText"))
            assertFalse(json.has("blockId"))
            assertFalse(json.has("webLinkUrl"))
        }
    }

    @Nested
    @DisplayName("생성 시점 규격 검증(fail-fast)")
    inner class Validation {

        private val thumb = SkillResponse.Thumbnail("https://numbers-baseball.com/images/answer.png")

        @Test
        @DisplayName("버튼이 3개를 초과하면 예외")
        fun rejectTooManyButtons() {
            val four = (1..4).map { SkillResponse.Button.message("b$it", "b$it") }
            assertThrows(IllegalArgumentException::class.java) {
                SkillResponse.BasicCard(thumbnail = thumb, buttons = four)
            }
        }

        @Test
        @DisplayName("description이 230자를 초과하면 예외")
        fun rejectLongDescription() {
            assertThrows(IllegalArgumentException::class.java) {
                SkillResponse.BasicCard(thumbnail = thumb, description = "가".repeat(231))
            }
        }

        @Test
        @DisplayName("title이 50자를 초과하면 예외")
        fun rejectLongTitle() {
            assertThrows(IllegalArgumentException::class.java) {
                SkillResponse.BasicCard(thumbnail = thumb, title = "가".repeat(51))
            }
        }

        @Test
        @DisplayName("경계값(title 50 / desc 230 / 버튼 3)은 허용")
        fun acceptBoundary() {
            SkillResponse.BasicCard(
                thumbnail = thumb,
                title = "가".repeat(50),
                description = "가".repeat(230),
                buttons = (1..3).map { SkillResponse.Button.message("b$it", "b$it") },
            )
        }
    }
}
