# 숫자야구 카카오톡 챗봇 기획 문서

> **목표(내일까지): 기본+부가기능 게임을 H2(DB)에 저장하고, 클라우드에 HTTPS 실배포 → 오픈빌더 연동 완료**
> 기술: Kotlin + Spring Boot + JPA / H2(개발) → MySQL(운영 선택) / 클라우드 배포

---

## ⚠️ 먼저: 일정 현실 점검

선택하신 범위(DB 저장 + 실배포 + 부가기능)는 **하루치고는 빡빡**합니다. 그래서 아래처럼 **2티어**로 나눴습니다.

| 티어 | 내용 | 비고 |
|------|------|------|
| 🔴 **필수 (내일 반드시)** | Spring Boot 전환 → 판정 로직(TDD) → DB 저장 → 컨트롤러 → **실배포(HTTPS)** → 오픈빌더 연동 | 이것만 되면 "동작하는 배포된 챗봇" 완성 |
| 🟡 **여유 되면** | 힌트 / 시도 제한 / 자릿수 선택 / 전적·랭킹 / 모니터링 | 시간 남을 때 추가 |

> **전략 제안**: DB는 **H2 파일 모드**로 먼저 띄워서 빠르게 배포까지 도달하고, 운영 안정화는 그 다음 MySQL 전환을 고려하세요. 처음부터 MySQL+RDS를 붙이면 배포 전에 시간을 다 씁니다.

---

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| 게임 | 숫자야구 (기본 **4자리** + 부가기능) |
| 채널 | 카카오톡 채널 + 오픈빌더 스킬 서버 |
| 서버 | Kotlin / Spring Boot 3.x (Web + JPA) |
| 상태 저장 | **DB (H2 → MySQL)**, `userId` 기준 게임 세션 |
| 배포 | 클라우드 + HTTPS (도메인/리버스 프록시) |
| 응답 제한 | **5초 이내** (카카오 타임아웃) |

### 현재 상태 (시작점 — 실측)
- **순수 Kotlin/JVM `application` 프로젝트** (Spring Boot 아님)
- `build.gradle.kts`: `kotlin("jvm") 2.1.10`, 의존성은 `kotlin.test`만
- `Main.kt`: `println("Hello World!")`
- Gradle 8.10 / JVM 21

→ **1번 작업: build.gradle.kts를 Spring Boot 3.x로 전환** (JVM 21이라 Boot 3.x 적합)

---

## 2. 게임 규칙 (기본 + 부가기능)

### 기본
- 정답: 0~9 중 **서로 다른 숫자 4자리** (예: `5273`) — 국룰
- **스트라이크(S)**: 숫자+자리 일치 / **볼(B)**: 숫자만 일치 / **아웃**: `0S 0B`
- `4S`이면 종료 + 시도 횟수 안내

### 부가기능 (🟡 여유 되면)
| 기능 | 설명 |
|------|------|
| 시도 제한 | 예: 10회 초과 시 실패 처리 + 정답 공개 |
| 힌트 | 첫 자리 또는 포함 숫자 1개 공개 (힌트당 패널티) |
| 자릿수 선택 | 3자리/4자리/5자리 선택 (`난이도` 명령어) — 기본 4자리 |
| 전적/랭킹 | 승률, 평균 시도 횟수, 최소 시도 랭킹 (DB 집계) |

### 명령어
- `시작`·`새게임` → 새 정답 / `포기` → 정답 공개 / `전적` → 통계 / 4자리 숫자 → 추측

---

## 3. 아키텍처

### 요청 흐름 (순서)
```
1. 사용자가 카톡에서 "123" 입력
2. 오픈빌더 블록 → 스킬 서버로 POST 호출
3. 카카오 → POST /skill/play  (JSON: utterance, user.id)
4. Controller가 JSON 수신
5. GameService: userId로 진행중 게임 DB 조회
6. BaseballJudge: 판정(S/B/O) → Game 엔티티 갱신 → DB 저장(JPA)
7. 카카오 스킬 응답 JSON 생성 (version 2.0 / simpleText)
8. 반환 → 카카오가 말풍선 렌더링
```

### 패키지 구조 (제안)
```
src/main/kotlin/com/example/baseball/
├── BaseballApplication.kt
├── controller/SkillController.kt        # POST /skill/play
├── service/
│   ├── GameService.kt                   # 흐름 + 세션(DB) 관리
│   └── BaseballJudge.kt                 # 순수 판정 로직 (테스트 용이)
├── domain/
│   ├── Game.kt                          # @Entity: id, userId, answer, tries, status
│   └── GameRepository.kt                # JpaRepository
└── dto/
    ├── SkillRequest.kt                  # 카카오 → 서버
    └── SkillResponse.kt                 # 서버 → 카카오
src/main/resources/application.yml
```

> **설계 의도**: `BaseballJudge`(판정)를 스프링/카카오/DB와 분리한 **순수 함수**로 두어 단위 테스트로 빠르게 검증. (평소 강조하시는 코드 품질·테스트 커버리지 지표 충족) DB I/O는 `GameService`로 격리.

---

## 4. 데이터 모델

### Game 엔티티 (핵심 컬럼)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | Long PK | 게임 ID |
| userId | String (index) | 카카오 user.id |
| answer | String | 정답 (예: "5273") |
| digits | Int | 자릿수 (기본 4) |
| tries | Int | 시도 횟수 |
| status | Enum | PLAYING / WON / GIVEUP / FAILED |
| createdAt / finishedAt | Timestamp | 통계용 |

> **인덱스 주의**: `userId + status=PLAYING` 조회가 매 요청마다 발생 → `userId` 인덱스 필수. (5초 제한 + 평소 쿼리 최적화 관점)

### 카카오 요청/응답 (필요한 필드만)
```json
// 받는 요청
{ "userRequest": { "utterance": "1234", "user": { "id": "abc123" } } }

// 보내는 응답
{ "version": "2.0", "template": { "outputs": [ { "simpleText": { "text": "1S 1B" } } ] } }
```

---

## 5. 순차 작업 체크리스트

> 위→아래 순서. 각 단계 끝 "확인"으로 검증. 🔴=필수, 🟡=여유 되면.

### 🔴 STEP 1. Spring Boot 전환 (30분)
- [ ] `build.gradle.kts`: `org.springframework.boot` 3.x + `io.spring.dependency-management` + `kotlin("plugin.spring")` + `kotlin("plugin.jpa")`
- [ ] 의존성: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `jackson-module-kotlin`, `com.h2database:h2`, `spring-boot-starter-test`
- [ ] `application` 플러그인 / `Main.kt` 제거
- [ ] **확인**: `./gradlew build` 성공

### 🔴 STEP 2. 부트스트랩 + DB (15분)
- [ ] `BaseballApplication.kt` (`@SpringBootApplication`)
- [ ] `application.yml`: `server.port`, H2 **파일 모드** datasource, `jpa.hibernate.ddl-auto: update`
- [ ] **확인**: `./gradlew bootRun` 기동 + H2 콘솔 접속

### 🔴 STEP 3. 판정 로직 + 테스트 (40분) ⭐ TDD
- [ ] `BaseballJudge.judge(answer, guess): Result(strike, ball)`
- [ ] 입력 검증: 자릿수 / 숫자만 / 중복 없음
- [ ] 단위 테스트: `5273`vs`5283`→3S0B, `5273`vs`2735`→0S4B, `5273`vs`1289`→1S0B(2), 예외 케이스
- [ ] **확인**: `./gradlew test` 통과

### 🔴 STEP 4. 도메인 + 세션(DB) (30분)
- [ ] `Game` 엔티티 + `GameRepository`
- [ ] `GameService`: 진행중 게임 조회 / 새 게임 / 추측 / 포기 + 랜덤 정답 생성
- [ ] **확인**: 서비스 테스트(선택) 또는 H2 콘솔에서 row 확인

### 🔴 STEP 5. 스킬 컨트롤러 (30분)
- [ ] `SkillRequest`/`SkillResponse` DTO
- [ ] `SkillController` `POST /skill/play`: utterance → 명령어/숫자 분기 → 응답
- [ ] **확인**:
  ```bash
  curl -X POST localhost:8080/skill/play -H "Content-Type: application/json" \
    -d '{"userRequest":{"utterance":"1234","user":{"id":"u1"}}}'
  ```

### 🔴 STEP 6. 실배포 (HTTPS) (60~90분) — 가장 큰 변수
- [ ] 클라우드 인스턴스 준비 (예: 가벼운 VM / 무료 등급)
- [ ] 빌드 산출물 배포: `./gradlew bootJar` → 서버에서 `java -jar`
- [ ] **HTTPS**: 도메인 연결 + 리버스 프록시(Nginx) + Let's Encrypt, 또는 HTTPS 기본 제공 플랫폼 사용
- [ ] **확인**: `https://내도메인/skill/play` curl 성공 (200 + 응답 JSON)

> 💡 **시간 단축 팁**: 도메인/SSL 직접 구축이 부담되면, HTTPS를 기본 제공하는 PaaS(컨테이너 배포형)를 쓰면 STEP 6을 크게 줄일 수 있습니다. 이게 오늘의 최대 리스크라 여기에 시간을 몰아주세요.

### 🔴 STEP 7. 오픈빌더 연동 (40분)
- [ ] 카카오 비즈니스 채널 + 오픈빌더 봇 생성
- [ ] 스킬 등록: URL = `https://내도메인/skill/play`
- [ ] 블록 + 폴백 블록에 스킬 연결
- [ ] **확인**: 봇 테스트에서 숫자 입력 → 판정 응답

### 🔴 STEP 8. 마무리 점검
- [ ] 5초 내 응답 / 예외 입력(빈값·자릿수 오류·문자) 처리 / README 작성

### 🟡 STEP 9. 부가기능 (시간 남으면)
- [ ] 시도 제한 → 힌트 → 자릿수 선택 → 전적/랭킹(DB 집계 쿼리)

### 🟡 STEP 10. 운영 강화 (이후)
- [ ] H2 → MySQL 전환 / 요청 로깅 + 응답시간 메트릭 / 모니터링·알림

---

## 6. 리스크 & 주의사항

| 리스크 | 영향 | 대응 |
|--------|------|------|
| **실배포 HTTPS 구축** | 🔴 가장 큰 시간 소모 | HTTPS 기본 제공 플랫폼 활용 / Nginx+Certbot 사전 숙지 |
| 5초 타임아웃 | 응답 실패 | DB 조회는 `userId` 인덱스로 경량화, 콜드스타트 주의 |
| H2 파일 영속성 | 배포 재시작 시 데이터 | 파일 모드 사용, 운영은 MySQL로 전환 |
| 동시성 | 같은 user 동시 요청 | 게임당 단일 진행 row + 트랜잭션 처리 |
| 범위 과다 | 미완성 위험 | 🔴만 먼저 끝내고 🟡은 후순위 |

---

## 7. 요약

- 현재는 **순수 Kotlin Hello World** → **1번이 Spring Boot+JPA 전환**.
- 선택 범위(DB+실배포+부가기능)는 하루에 빡빡 → **🔴 필수(배포까지)** 먼저, **🟡 부가기능**은 후순위.
- 8단계 필수 흐름: 빌드 전환 → 부트스트랩+DB → 판정(TDD) → 세션(DB) → 컨트롤러 → **실배포(HTTPS, 최대 리스크)** → 오픈빌더 → 점검.
- DB는 **H2 파일 모드로 빠르게 배포 도달** 후 MySQL 전환 권장.
- 판정 로직을 순수 함수로 분리해 **테스트 커버리지·코드 품질** 확보, `userId` 인덱스로 **5초 제한** 대비.
