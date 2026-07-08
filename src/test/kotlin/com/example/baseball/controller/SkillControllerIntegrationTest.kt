package com.example.baseball.controller

import com.example.baseball.domain.game.GameRepository
import com.example.baseball.domain.game.GameStatus
import com.example.baseball.domain.user.BotUser
import com.example.baseball.domain.user.BotUserRepository
import com.example.baseball.domain.user.User
import com.example.baseball.domain.user.UserRepository
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
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
) {
    /** 카카오 스킬 요청 본문 생성 (botKey 선택적으로 chat.properties.botGroupKey 포함) */
    private fun body(utterance: String, userId: String, botKey: String? = null): String {
        val chatPart = botKey?.let { ""","chat":{"properties":{"botGroupKey":"$it"}}""" } ?: ""
        return """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId"}$chatPart}}"""
    }

    /** /skill/play 호출 후 simpleText.text 가 expected 를 포함하는지 검증 */
    private fun play(utterance: String, userId: String, expectedText: String, botKey: String? = null) {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(utterance, userId, botKey)
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value("2.0") }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString(expectedText)) }
        }
    }

    /** 랭킹용 시드: User(FK) 저장 후 BotUser(botKey, botUserKey, score) 저장 */
    private fun seedBotUser(botKey: String, botUserKey: String, score: Int) {
        val user = userRepository.save(User(appUserId = "app-$botUserKey"))
        botUserRepository.save(
            BotUser(user = user, botUserKey = botUserKey, botKey = botKey, score = score)
        )
    }

    @Test
    @DisplayName("시작 → 추측(정답) → 승리까지 세션이 이어진다")
    fun startGuessWin() {
        val userId = "it-user-win"

        // 1) 시작
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)
        assertNotNull(game) // DB에 진행중 게임 생성됨

        // 2) DB의 실제 정답으로 추측 → 승리 (STEP-11 연출 문구는 가변이므로 안정적 키워드 "정답"만 검증)
        play(game.answer, userId, "정답")

        // 3) 상태가 WON 으로 전이되었는지 확인 (이전 요청이 커밋했으므로 재조회로 검증)
        val finished = gameRepository.findById(game.id!!).orElseThrow()
        assertEquals(GameStatus.WON, finished.status)
        assertEquals(1, finished.tries)
    }

    @Test
    @DisplayName("정답 승리: 오픈채팅(botKey 있음)에서는 승자를 멘션한다")
    fun winMentionsWinnerInOpenChat() {
        val userId = "it-user-winmention"
        val botKey = "it-bot-winmention"

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", userId, botKey = botKey)
        }.andExpect { status { isOk() } }
        val game = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)!!

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(game.answer, userId, botKey = botKey)
        }.andExpect {
            status { isOk() }
            // 이미지 URL 미설정 → simpleText 폴백 경로. 승자 멘션 자리표시자 + extra.mentions.winner 를 담는다.
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("{{#mentions.winner}}")) }
            jsonPath("$.extra.mentions.winner.type") { value("botUserKey") }
            jsonPath("$.extra.mentions.winner.id") { value(userId) } // botUserKey == userId
        }
    }

    @Test
    @DisplayName("시작 → 오답이면 PLAYING 유지, 시도 횟수 증가")
    fun startWrongGuess() {
        val userId = "it-user-wrong"
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)!!

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
        val game = gameRepository.findFirstByBotKeyAndStatus(userId, GameStatus.PLAYING)!!

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
    @DisplayName("알 수 없는 발화는 사용법을 반환한다")
    fun helpMessage() {
        play("안녕", "it-user-help", "사용법")
    }

    @Test
    @DisplayName("'게임 규칙'은 STRIKE/BALL/OUT 규칙을 반환한다")
    fun rulesMessage() {
        play("게임 규칙", "it-user-rules", "STRIKE")
    }

    @Test
    @DisplayName("랭킹: 시드된 점수가 내림차순 TOP 텍스트로 응답된다")
    fun rankingSortedByScore() {
        val botKey = "it-bot-rank"
        seedBotUser(botKey, "low", 100)
        seedBotUser(botKey, "high", 300)

        // 1위가 high(300), 점수 텍스트가 포함되는지 확인
        play("랭킹", "any-user", "1위", botKey = botKey)
        play("랭킹", "any-user", "300점", botKey = botKey)
    }

    @Test
    @DisplayName("랭킹: 이름을 멘션 자리표시자로 두고 extra.mentions에 botUserKey를 등록한다")
    fun rankingUsesMentions() {
        val botKey = "it-bot-mention"
        seedBotUser(botKey, "userkey-high", 300)
        seedBotUser(botKey, "userkey-low", 100)

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("랭킹", "any-user", botKey = botKey)
        }.andExpect {
            status { isOk() }
            // 1위 줄은 원시 키가 아니라 멘션 자리표시자를 담는다.
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("1위  {{#mentions.user1}}  300점")) }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("2위  {{#mentions.user2}}  100점")) }
            // extra.mentions 에 순위별 botUserKey 가 등록된다.
            jsonPath("$.extra.mentions.user1.type") { value("botUserKey") }
            jsonPath("$.extra.mentions.user1.id") { value("userkey-high") }
            jsonPath("$.extra.mentions.user2.id") { value("userkey-low") }
        }
    }

    @Test
    @DisplayName("랭킹: 해당 봇에 점수가 없으면 안내 메시지")
    fun rankingEmpty() {
        play("랭킹", "any-user", "아직 랭킹에 등록된 점수가 없습니다", botKey = "it-bot-empty")
    }

    @Test
    @DisplayName("랭킹: bot 정보가 없으면 안내 메시지")
    fun rankingNoBotKey() {
        play("랭킹", "any-user", "채팅방 정보를 확인할 수 없어")
    }

    /** 정답과 다른, 서로 다른 숫자 4자리 추측을 하나 생성 */
    private fun firstValidGuessDifferentFrom(answer: String): String {
        val candidates = listOf("0123", "4567", "8901", "2345", "6789")
        return candidates.first { it != answer }
    }
}
