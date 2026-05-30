package com.example.baseball

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BaseballApplication

fun main(args: Array<String>) {
    runApplication<BaseballApplication>(*args)
}
