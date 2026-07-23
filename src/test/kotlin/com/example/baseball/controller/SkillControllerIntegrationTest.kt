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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("스킬 컨트롤러 통합 테스트 (/skill/play)")
class SkillControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val gameRepository: GameRepository,
    private val userRepository: UserRepository,
    private val botUserRepository: BotUserRepository,
) {
    /**
     * 카카오 스킬 요청 본문. 게임이 방 단위라 botKey 를 고정하면 테스트끼리 서로의 게임을 끊는다.
     * 그래서 방 키를 유저별로 파생시켜 기존의 테스트 간 격리를 그대로 유지한다.
     */
    private fun body(
        utterance: String,
        userId: String,
        botKey: String = roomOf(userId),
    ): String =
        """{"userRequest":{"utterance":"$utterance","user":{"id":"$userId","properties":{"appUserId":"app-$userId","botUserKey":"$userId"}},"chat":{"properties":{"botGroupKey":"$botKey"}}}}"""

    private fun roomOf(userId: String) = "bot-$userId"

    private fun play(
        utterance: String,
        userId: String,
        expectedText: String,
        botKey: String = roomOf(userId),
    ) {
        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(utterance, userId, botKey)
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value("2.0") }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString(expectedText)) }
        }
    }

    /** 랭킹용 시드: User(FK, score=score) 저장 후 BotUser(botKey, botUserKey) 저장 */
    private fun seedBotUser(botKey: String, botUserKey: String, score: Int) {
        val user = userRepository.save(User(appUserId = "app-$botUserKey").apply { this.score = score })
        botUserRepository.save(
            BotUser(user = user, botUserKey = botUserKey, botKey = botKey)
        )
    }

    @Test
    @DisplayName("시작 → 추측(정답) → 승리까지 세션이 이어진다")
    fun startGuessWin() {
        val userId = "it-user-win"

        // 1) 시작
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(roomOf(userId), GameStatus.PLAYING)
        assertNotNull(game) // DB에 진행중 게임 생성됨

        // 2) DB의 실제 정답으로 추측 → 승리 (STEP-11 연출 문구는 가변이므로 안정적 키워드 "정답"만 검증)
        play(game.answer, userId, "정답")

        // 3) 상태가 WON 으로 전이되었는지 확인 (이전 요청이 커밋했으므로 재조회로 검증)
        val finished = gameRepository.findById(game.id!!).orElseThrow()
        assertEquals(GameStatus.WON, finished.status)
        assertEquals(1, finished.tries)
    }

    @Test
    @DisplayName("추측: 멘션 프리필이 남긴 제로폭 공백이 앞에 붙어도 숫자로 판정한다")
    fun guessStripsZeroWidthPrefix() {
        val userId = "it-user-zwsp"
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(roomOf(userId), GameStatus.PLAYING)!!

        // 프리필 잔여물(U+200B)이 추측 앞에 섞여 들어온 상황을 재현: "\u200B<정답>"
        play("\u200B${game.answer}", userId, "정답")
        assertEquals(GameStatus.WON, gameRepository.findById(game.id!!).orElseThrow().status)
    }

    @Test
    @DisplayName("정답 승리: 첫 줄에 정답을 노출하고 승자 멘션은 넣지 않는다")
    fun winRevealsAnswerWithoutMention() {
        val userId = "it-user-winmention"
        val botKey = "it-bot-winmention"

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body("시작", userId, botKey = botKey)
        }.andExpect { status { isOk() } }
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(botKey, GameStatus.PLAYING)!!

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = body(game.answer, userId, botKey = botKey)
        }.andExpect {
            status { isOk() }
            // 승리 첫 줄은 실제 정답을 노출한다.
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("정답 ${game.answer}")) }
            // 승자 멘션은 넣지 않으므로 extra(mentions)가 없어야 한다.
            jsonPath("$.extra") { doesNotExist() }
        }
    }

    @Test
    @DisplayName("시작 → 오답이면 PLAYING 유지, 시도 횟수 증가")
    fun startWrongGuess() {
        val userId = "it-user-wrong"
        play("시작", userId, "새 게임")
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(roomOf(userId), GameStatus.PLAYING)!!

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
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(roomOf(userId), GameStatus.PLAYING)!!

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
    @DisplayName("알 수 없는 발화는 게임 규칙 안내를 반환한다")
    fun helpMessage() {
        play("안녕", "it-user-help", "STRIKE")
    }

    @Test
    @DisplayName("게임 규칙은 STRIKE/BALL/OUT 규칙 요약을 포함한다(규칙+사용법 통합)")
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
    @DisplayName("방 단위: 다른 사람이 시작하면 진행중이던 게임을 종료하고 그 정답을 알린다")
    fun startReplacesRoomGame() {
        val room = "it-bot-replace"
        play("시작", "starter", "새 게임", botKey = room)
        val first = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!

        play("시작", "intruder", "진행 중이던 게임(정답 ${first.answer})을 종료했어요", botKey = room)

        assertEquals(GameStatus.GIVEUP, gameRepository.findById(first.id!!).orElseThrow().status)
        val second = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    @DisplayName("방 단위: A가 시작한 게임을 B가 맞히면 점수는 B에게 간다")
    fun winnerGetsScore() {
        val room = "it-bot-winner"
        play("시작", "opener", "새 게임", botKey = room)
        val game = gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING)!!

        play(game.answer, "finisher", "정답", botKey = room)

        assertEquals(0, userRepository.findByAppUserId("app-opener")!!.score)
        assertTrue(userRepository.findByAppUserId("app-finisher")!!.score > 0)
    }

    @Test
    @DisplayName("appUserId 가 없는 페이로드는 안내 응답만 주고 게임을 만들지 않는다")
    fun missingAppUserIdIsRejected() {
        val room = "it-bot-noappid"
        val payload =
            """{"userRequest":{"utterance":"시작","user":{"id":"no-appid"},"chat":{"properties":{"botGroupKey":"$room"}}}}"""

        mockMvc.post("/skill/play") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
            jsonPath("$.template.outputs[0].simpleText.text") { value(containsString("잠시 후 다시 시도")) }
        }

        assertNull(gameRepository.findFirstByBotKeyAndStatusOrderByIdDesc(room, GameStatus.PLAYING))
    }

    /** 정답과 다른, 서로 다른 숫자 4자리 추측을 하나 생성 */
    private fun firstValidGuessDifferentFrom(answer: String): String {
        val candidates = listOf("0123", "4567", "8901", "2345", "6789")
        return candidates.first { it != answer }
    }
}
