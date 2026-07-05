package com.example.baseball.controller

import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 이미지 URL이 설정된 상황(=prod 유사)에서 START/GUESS가 BasicCard로 나가는지 검증한다.
 * 기본 통합 테스트(SkillControllerIntegrationTest)는 URL 미설정 → simpleText 폴백 경로를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["kakao.image-base-url=https://img.test/images"])
@DisplayName("스킬 컨트롤러 - BasicCard 경로 (이미지 URL 설정 시)")
class SkillControllerCardTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val gameRepository: GameRepository,
) {
    private fun body(utterance: String, userId: String): String =
        """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId"}}}"""

    @Test
    @DisplayName("시작 응답은 start 썸네일 + 버튼을 가진 basicCard 다")
    fun startReturnsCard() {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", "card-start")
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].simpleText") { doesNotExist() }
            jsonPath("$.template.outputs[0].basicCard.thumbnail.imageUrl") {
                value("https://img.test/images/start.png")
            }
            jsonPath("$.template.outputs[0].basicCard.buttons[0].action") { value("message") }
        }
    }

    @Test
    @DisplayName("정답 추측 승리 응답은 win 썸네일 + [다시 도전, 랭킹 보기] 버튼이다")
    fun winReturnsWinCard() {
        val userId = "card-win"
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", userId)
        }.andExpect { status { isOk() } }

        val answer = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)!!.answer

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(answer, userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].basicCard.thumbnail.imageUrl") {
                value("https://img.test/images/answer.png")
            }
            jsonPath("$.template.outputs[0].basicCard.buttons.length()") { value(2) }
            jsonPath("$.template.outputs[0].basicCard.buttons[0].messageText") { value("시작") }
            jsonPath("$.template.outputs[0].basicCard.buttons[1].messageText") { value("랭킹") }
        }
    }
}
