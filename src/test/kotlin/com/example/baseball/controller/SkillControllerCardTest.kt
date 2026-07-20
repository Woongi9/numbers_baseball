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
@TestPropertySource(
    properties = [
        "kakao.image-base-url=https://img.test/images",
        "kakao.mention-button-label=@테스트봇에게 말하기",
    ],
)
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
                value("https://img.test/images/start.png?v=4")
            }
            // 시작 이미지는 2:1 배너 → fixedRatio=false(네이티브 2:1, 크롭 없음)
            jsonPath("$.template.outputs[0].basicCard.thumbnail.fixedRatio") { value(false) }
            // 시작 카드 버튼은 [포기, 제출(멘션 프리필)] 순이다(제출을 우측에 둔다).
            jsonPath("$.template.outputs[0].basicCard.buttons.length()") { value(2) }
            jsonPath("$.template.outputs[0].basicCard.buttons[0].label") { value("포기") }
            jsonPath("$.template.outputs[0].basicCard.buttons[1].label") { value("제출") }
            jsonPath("$.template.outputs[0].basicCard.buttons[1].action") { value("mention") }
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
                value("https://img.test/images/answer.png?v=4")
            }
            jsonPath("$.template.outputs[0].basicCard.buttons.length()") { value(2) }
            jsonPath("$.template.outputs[0].basicCard.buttons[0].messageText") { value("랭킹") }
            jsonPath("$.template.outputs[0].basicCard.buttons[1].messageText") { value("시작") }
        }
    }

    @Test
    @DisplayName("오답(진행중) 응답은 썸네일 없는 textCard + 멘션 프리필용 '제출' 버튼 하나다")
    fun ongoingReturnsSubmitButton() {
        val userId = "card-ongoing"
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", userId)
        }.andExpect { status { isOk() } }

        val answer = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)!!.answer
        val wrong = listOf("0123", "4567", "8901", "2345").first { it != answer }

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(wrong, userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].basicCard") { doesNotExist() } // 썸네일 없음
            jsonPath("$.template.outputs[0].textCard.buttons.length()") { value(1) }
            jsonPath("$.template.outputs[0].textCard.buttons[0].label") { value("제출") }
            jsonPath("$.template.outputs[0].textCard.buttons[0].action") { value("mention") }
        }
    }

    @Test
    @DisplayName("안내(예외) 카드의 멘션 버튼 라벨은 설정값(kakao.mention-button-label)을 따른다")
    fun guideCardUsesConfiguredMentionLabel() {
        // 진행 중 게임 없이 추측 → IllegalStateException → 안내 TextCard
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("1234", "card-guide")
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].textCard.buttons[0].label") { value("@테스트봇에게 말하기") }
            jsonPath("$.template.outputs[0].textCard.buttons[0].action") { value("mention") }
        }
    }

    @Test
    @DisplayName("게임 규칙 응답은 썸네일 없는 textCard + '시작' 버튼이다")
    fun rulesReturnsTextCardWithStart() {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("게임 규칙", "card-rules")
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].basicCard") { doesNotExist() } // 썸네일 없음
            jsonPath("$.template.outputs[0].textCard.title") { value("⚾ 숫자야구 규칙") }
            jsonPath("$.template.outputs[0].textCard.description") { value(org.hamcrest.Matchers.containsString("STRIKE")) }
            jsonPath("$.template.outputs[0].textCard.buttons.length()") { value(1) }
            jsonPath("$.template.outputs[0].textCard.buttons[0].label") { value("시작") }
            jsonPath("$.template.outputs[0].textCard.buttons[0].messageText") { value("시작") }
        }
    }

    @Test
    @DisplayName("포기 응답은 giveup 썸네일 basicCard + [게임 규칙, 시작] 버튼이다")
    fun giveUpReturnsCard() {
        val userId = "card-giveup"
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", userId)
        }.andExpect { status { isOk() } }

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("포기", userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].basicCard.thumbnail.imageUrl") {
                value("https://img.test/images/giveup.png?v=4")
            }
            jsonPath("$.template.outputs[0].basicCard.title") { value("🏳️ 게임 포기") }
            jsonPath("$.template.outputs[0].basicCard.buttons.length()") { value(2) }
            jsonPath("$.template.outputs[0].basicCard.buttons[0].messageText") { value("게임 규칙") }
            jsonPath("$.template.outputs[0].basicCard.buttons[1].messageText") { value("시작") }
        }
    }
}
