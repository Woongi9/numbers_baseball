package com.example.baseball.domain.game

enum class GameDifficulty (val multiplier : Double, val symbols: List<Char>) {
    HARD(2.0, ('0'..'9').toList() + ('a'..'e').toList()),  // 어려움 : 0 ~ 9 에 abcde 추가
    NORMAL(1.0, ('0'..'9').toList()),  // 보통 : 0 ~ 9
    EASY(0.5, ('0'..'5').toList());  // 쉬움 : 0 ~ 5

    fun multiplier(): Double {
        return multiplier
    }
}