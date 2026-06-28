package com.example.baseball.domain.user

import org.springframework.data.jpa.repository.JpaRepository

// User.id 는 Long PK 이므로 JpaRepository 의 ID 타입도 Long 이어야 한다.
interface UserRepository : JpaRepository<User, Long> {

    /** 전역 식별자(appUserId)로 1건 조회. score 적립 시 getOrCreate 의 조회 단계에 사용. */
    fun findByAppUserId(appUserId: String): User?
}
