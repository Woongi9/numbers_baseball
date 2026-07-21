package com.example.baseball.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("SkillCommand.classify - 발화 분류")
class SkillCommandTest {

    @Test
    @DisplayName("시작 계열 단어 → START")
    fun start() {
        listOf("시작", "새게임", "시작하기").forEach {
            assertEquals(SkillCommand.START, SkillCommand.classify(it))
        }
    }

    @Test
    @DisplayName("'포기' → GIVEUP")
    fun giveUp() {
        assertEquals(SkillCommand.GIVEUP, SkillCommand.classify("포기"))
    }

    @Test
    @DisplayName("랭킹 계열 단어 → RANKING")
    fun ranking() {
        listOf("랭킹", "봇랭킹", "순위").forEach {
            assertEquals(SkillCommand.RANKING, SkillCommand.classify(it))
        }
    }

    @Test
    @DisplayName("숫자만 있는 발화 → GUESS")
    fun guess() {
        assertEquals(SkillCommand.GUESS, SkillCommand.classify("1234"))
        assertEquals(SkillCommand.GUESS, SkillCommand.classify("5"))
    }

    @Test
    @DisplayName("앞뒤 공백은 무시하고 분류한다")
    fun trimsWhitespace() {
        assertEquals(SkillCommand.START, SkillCommand.classify("  시작 "))
        assertEquals(SkillCommand.GUESS, SkillCommand.classify(" 1234 "))
    }

    @Test
    @DisplayName("규칙·사용법 계열 단어 → HELP ('게임 규칙'으로 통합, '도움말'은 카카오 예약 발화라 제외)")
    fun helpWords() {
        listOf("게임규칙", "게임 규칙", "규칙", "사용법").forEach {
            assertEquals(SkillCommand.HELP, SkillCommand.classify(it))
        }
    }

    @Test
    @DisplayName("그 외(빈 문자열·일반 텍스트·숫자+문자 혼합) → HELP")
    fun help() {
        assertEquals(SkillCommand.HELP, SkillCommand.classify(""))
        assertEquals(SkillCommand.HELP, SkillCommand.classify("   "))
        assertEquals(SkillCommand.HELP, SkillCommand.classify("안녕"))
        assertEquals(SkillCommand.HELP, SkillCommand.classify("12a4")) // 숫자만이 아님
    }
}
