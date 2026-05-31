package com.example.baseball.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun baseballOpenAPI(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("숫자야구 챗봇 API")
                .description("카카오 오픈빌더 스킬 서버. /skill/play 로 발화(utterance)를 받아 게임을 진행한다.")
                .version("v1"),
        )
}
