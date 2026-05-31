package com.example.baseball.domain

enum class GameStatus {
    PLAYING,  // 진행 중
    WON,      // 정답 맞힘
    GIVEUP,   // 사용자 포기
    FAILED,   // 시도 횟수 초과 실패(부가기능에서 사용)
}
