package com.example.baseball

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class BaseballApplication

fun main(args: Array<String>) {
    runApplication<BaseballApplication>(*args)
}
