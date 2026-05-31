package com.example.baseball.controller

import com.example.baseball.domain.GameRepository
import com.example.baseball.domain.GameStatus
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("스킬 컨트롤러 통합 테스트 (/skill/play)")
class SkillControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val gameRepository: GameRepository,
) {
    /** 카카오 스킬 요청 본문 생성 */
    private fun body(utterance: String, userId: String) =
        """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId"}}}"""

    /** /skill/play 호출 후 simpleText.text 가 expected 를 포함하는지 검증 */
    private fun play(utterance: String, userId: String, expectedText: String) {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(utterance, userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value("2.0") }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString(expectedText)) }
        }
    }

    @Test
    @DisplayName("시작 → 추측(정답) → 승리까지 세션이 이어진다")
    fun startGuessWin() {
        val userId = "it-user-win"

        // 1) 시작
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING)
        assertNotNull(game) // DB에 진행중 게임 생성됨

        // 2) DB의 실제 정답으로 추측 → 승리
        play(game.answer, userId, "정답입니다")

        // 3) 상태가 WON 으로 전이되었는지 확인 (이전 요청이 커밋했으므로 재조회로 검증)
        val finished = gameRepository.findById(game.id!!).orElseThrow()
        assertEquals(GameStatus.WON, finished.status)
        assertEquals(1, finished.tries)
    }

    @Test
    @DisplayName("시작 → 오답이면 PLAYING 유지, 시도 횟수 증가")
    fun startWrongGuess() {
        val userId = "it-user-wrong"
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING)!!

        // 정답과 다른, 규칙에 맞는 추측 하나를 만든다(서로 다른 숫자 4자리)
        val wrong = firstValidGuessDifferentFrom(game.answer)
        play(wrong, userId, "시도")

        val after = gameRepository.findById(game.id!!).orElseThrow()
        assertEquals(GameStatus.PLAYING, after.status)
        assertEquals(1, after.tries)
    }

    @Test
    @DisplayName("시작 → 포기하면 정답을 공개하고 GIVEUP 으로 종료된다")
    fun startGiveUp() {
        val userId = "it-user-giveup"
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByUserIdAndStatus(userId, GameStatus.PLAYING)!!

        play("포기", userId, game.answer) // 응답에 정답이 포함됨

        val after = gameRepository.findById(game.id!!).orElseThrow()
        assertEquals(GameStatus.GIVEUP, after.status)
    }

    @Test
    @DisplayName("진행중 게임 없이 추측하면 안내 메시지를 반환한다")
    fun guessWithoutGame() {
        play("1234", "it-user-nogame", "진행 중인 게임이 없습니다")
    }

    @Test
    @DisplayName("규칙 위반 입력(중복 숫자)은 안내 메시지를 반환한다")
    fun invalidGuess() {
        val userId = "it-user-invalid"
        play("시작", userId, "새 게임")
        play("1123", userId, "서로 다른 숫자") // 중복 → 안내
    }

    @Test
    @DisplayName("알 수 없는 발화는 도움말을 반환한다")
    fun helpMessage() {
        play("안녕", "it-user-help", "숫자야구")
    }

    /** 정답과 다른, 서로 다른 숫자 4자리 추측을 하나 생성 */
    private fun firstValidGuessDifferentFrom(answer: String): String {
        val candidates = listOf("0123", "4567", "8901", "2345", "6789")
        return candidates.first { it != answer }
    }
}
