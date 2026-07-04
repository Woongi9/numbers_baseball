plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "1.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 요청 로깅(LogTrace)용 AOP. @Aspect/@Around + aspectjweaver 포함.
    implementation("org.springframework.boot:spring-boot-starter-aop")
    // 헬스체크/메트릭. 배포 헬스체크(/actuator/health)·CloudWatch 메트릭 노출에 사용.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Swagger UI (OpenAPI 3). Spring Boot 3.x는 springfox 대신 springdoc 사용.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // 운영/로컬 DB: MySQL (버전은 Spring Boot BOM이 관리)
    runtimeOnly("com.mysql:mysql-connector-j")
    // H2는 테스트(인메모리)에서만 사용 → 운영 JAR에 섞이지 않게 testRuntimeOnly
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

// 배포 시 파일명을 고정해 스크립트를 단순화 (build/libs/baseball.jar)
tasks.bootJar {
    archiveFileName.set("baseball.jar")
}

// -P profile=dev 로 넘기지 않으면 local. 빈 문자열도 local 처리.
val profile = (project.findProperty("profile") as String?)?.takeIf { it.isNotBlank() } ?: "local"

sourceSets {
    main {
        resources {
            // setSrcDirs(교체): 기본 경로(src/main/resources)에 '추가'하면
            // 같은 경로가 두 번 등록돼 application-prod.yml 이 중복 복사된다.
            // 교체로 각 디렉터리를 정확히 한 번씩만 등록.
            setSrcDirs(listOf("src/main/resources", "src/main/resources-env/$profile"))
        }
    }
}

