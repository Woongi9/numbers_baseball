# CloudWatch 모니터링 설계

- 날짜: 2026-07-11
- 상태: 설계 확정
- 범위: PLAN.md 6-D 발견2 `모니터링 -> 부하 테스트` 중 **모니터링** (부하 테스트 전 선행)

## 목적

출시 전(실사용자 0) 단계에서 서버/앱 이상을 이메일로 조기 감지한다. 모니터링을 먼저 깔아두면
이후 부하 테스트가 곧 이 대시보드/알람의 실검증이 된다. 앱 코드 변경 없음 — 100% AWS 인프라 작업.

## 전제 (기반)

원래 "이미 완료"로 적었으나 EC2 점검 결과 Agent/CLI/IAM 모두 미설치였음. 2026-07-12 아래를 실제 구축 완료:

- IAM 역할(신뢰 주체 EC2) 생성 + 인스턴스 `i-0d131fd9dd2bc8eee` 부착. 정책 3개
  (`CloudWatchAgentServerPolicy`, `CloudWatchFullAccessV2`, `AmazonSNSFullAccess`).
- EC2 에 AWS CLI 설치(`aws-cli/2.35.21`), `aws sts get-caller-identity` 역할로 정상 동작.
- CloudWatch Agent 설치 + 구동(`status: running`). config 는 `deploy/amazon-cloudwatch-agent.json`
  기준 — `mem_used_percent`/`disk_used_percent`(네임스페이스 `CWAgent`) 수집 +
  `/home/ubuntu/logs/server.log` 를 로그 그룹 `/baseball/app` 로 전송.

> **로그 파이프라인 실검증 + 권한 버그 수정(2026-07-12).** Agent 구동·config·IAM 다 정상인데
> 로그 그룹에 이벤트가 0건이었음(`describe-log-streams` = `[]`). 지표(`CWAgent`)는 정상이라
> 파일 읽기 경로만 고장으로 격리. 원인: Agent 는 `cwagent` 유저로 돌고, `/home/ubuntu` 가
> `0750 ubuntu:ubuntu` 라 `cwagent` 가 디렉터리를 통과(traverse)하지 못해 `server.log`(0644,
> world-readable)에 도달 불가. `server.log` 자체 권한은 문제 아님 — **부모 디렉터리 실행권한이 원인**.
> 수정: `sudo usermod -aG ubuntu cwagent` 후 Agent 재기동(그룹 반영은 새 프로세스부터).
> 검증: `sudo -u cwagent head /home/ubuntu/logs/server.log` 읽힘 → 재기동 후 스트림
> `i-0d131fd9dd2bc8eee` 생성, 최신 타임스탬프로 로그 유입 확인. 배포가 `server.log` 를 새로 만들어도
> 디렉터리 권한(0750/0775)·그룹 멤버십은 유지되므로 영구적.

앱/인프라 측 이미 존재:

- `application-prod.yml` — `logging.file.name: /home/ubuntu/logs/server.log` (Agent 가 tail 하는 파일).
- `LogTraceAspect` — 요청 1건당 한 줄 구조화 로그. 진짜 서버 장애만 `status=ERROR(...)`,
  정상 유저 입력 예외(자릿수·중복 틀린 추측, "게임 없음")는 `status=REJECTED(...)` 로 분리(커밋 24362b3).
  `slow=true` 토큰 포함.
- EC2 기본 지표(`AWS/EC2`)는 별도 설정 없이 `CPUUtilization`, `CPUCreditBalance` 제공.

남은 작업 = Metric Filter 2개 + 알람 5개 + SNS 토픽/구독 생성(= `deploy/monitoring/setup-cloudwatch.sh`).

## 접근

단일 커밋형 AWS CLI 스크립트(`deploy/monitoring/setup-cloudwatch.sh`) + README.
IaC(Terraform)는 EC2 1대 + RDS 1대 솔로 프로젝트엔 과함. 콘솔 클릭은 재현 불가. 스크립트가 중간.

## 데이터 흐름

```
앱(LogTraceAspect) -> /home/ubuntu/logs/server.log
      -> CloudWatch Agent -> Logs group /baseball/app
            -> Metric Filter(2개) -> 커스텀 지표 Baseball/App{error_count, slow_count}
CloudWatch Agent -> CWAgent 네임스페이스 -> mem_used_percent
EC2 기본 지표 -> AWS/EC2 -> CPUUtilization, CPUCreditBalance

5개 Alarm -> 1개 SNS 토픽 baseball-alarms -> 이메일 구독
```

## 산출물

- `deploy/monitoring/setup-cloudwatch.sh` — 멱등 AWS CLI 스크립트. SNS 토픽/구독 + Metric Filter 2개 + 알람 5개 생성.
- `deploy/monitoring/README.md` — 실행법, 임계치 표, 튜닝 노트, 구독 확인 클릭 안내.

### 스크립트 파라미터

| 이름 | 필수 | 기본값 | 비고 |
|------|------|--------|------|
| `ALARM_EMAIL` | O | (없음) | 알람 수신 이메일. **커밋 파일에 실주소 박지 않음**(레포 노출 방지). 개인 상용 주소 아닌 별도 주소. |
| `INSTANCE_ID` | O | (없음) | 대상 EC2. 미지정 시 명확히 실패(임의 인스턴스 방지). |
| `REGION` | X | `ap-northeast-2` | |
| `LOG_GROUP` | X | `/baseball/app` | |

`ALARM_EMAIL` 은 환경변수/인자로 주입. DB 접속정보를 레포에 안 넣는 프로젝트 원칙과 동일선.

## Metric Filter (2개)

로그 그룹 `/baseball/app`, 네임스페이스 `Baseball/App`. 매칭 시 값 1 방출, `defaultValue=0`.

| 지표명 | 필터 패턴 | 소스 토큰 |
|--------|-----------|-----------|
| `error_count` | `status=ERROR` | LogTraceAspect `status=ERROR(...)` |
| `slow_count` | `slow=true` | LogTraceAspect `slow=true` |

## 알람 5개 (임계치 = 모두 "시작값", 베이스라인 확보 후 튜닝)

| # | 알람 | 지표 | 통계/기간/평가 | 임계 | 근거 |
|---|------|------|----------------|------|------|
| 1 | 앱 에러 급증 | `Baseball/App/error_count` | Sum / 5분 / 1회 | > 10 | 정상 유저 입력 예외는 `REJECTED` 로 빠져 `error_count` 는 **진짜 서버 장애만** 셈(커밋 24362b3). 시작값 `>10` 은 보수적 — 베이스라인 확보 후 더 낮출 여지 있음. |
| 2 | 앱 지연 | `Baseball/App/slow_count` | Sum / 5분 / 1회 | >= 3 | `slow=true` = 3초(SLOW_THRESHOLD_MS) 초과. 카카오 5초 타임아웃 직전 신호. 소수만 떠도 조사 가치라 낮게. |
| 3 | 메모리 | `CWAgent/mem_used_percent` | Avg / 5분 / 2회(10분) | > 85% | t4g.small 2GB 에 prod+dev JVM 2개. 10분 지속만 잡아 GC 순간 스파이크 제외. |
| 4 | CPU 크레딧 고갈 | `AWS/EC2/CPUCreditBalance` | Avg / 5분 / 2회(10분) | < 60 | 버스터블(t4g)은 크레딧 소진 시 baseline 으로 스로틀. 잔량 하락 = m-계열 전환 신호. |
| 5 | CPU 사용률 | `AWS/EC2/CPUUtilization` | Avg / 5분 / 2회(10분) | > 70% | 무료 기본 지표. 크레딧 고갈 전 추세 조기 포착. 10분 지속으로 버스트 오탐 완화. |

모든 알람 `--alarm-actions` = SNS 토픽 ARN. `TreatMissingData` = `notBreaching`(데이터 없음 정상).

## 멱등성 / 에러 처리

- `set -euo pipefail`.
- `put-metric-filter` / `put-metric-alarm` = upsert. SNS `create-topic` 멱등(같은 이름 = 같은 ARN 반환).
- 필수 파라미터(`ALARM_EMAIL`, `INSTANCE_ID`) 미지정 시 usage 출력 후 즉시 종료.
- `--dry-run` 모드: 실제 생성 대신 실행할 CLI 를 출력(리뷰용).

## 검증

앱 코드 없음 → 통합 검증으로 대체.

1. 스크립트 실행 후 SNS 구독 확인 이메일 클릭(최초 1회 필수).
2. `aws cloudwatch describe-alarms` 로 5개 존재 확인.
3. `error_count` 실동작 확인. 주의: 잘못된 추측은 이제 `REJECTED` 라 안 잡힘 — 진짜 서버 예외
   (예: DB 다운)를 유발하거나, `slow_count` 는 3초 초과 요청으로 확인. Logs Insights 에서
   `/baseball/app` 로그 그룹 조회로 유입도 함께 확인.
4. Agent 구동 확인: `amazon-cloudwatch-agent-ctl -a status`.
5. 실부하 검증은 다음 STEP(부하 테스트)에서 알람 실동작으로 확인.

## 범위 밖 (YAGNI)

- Micrometer / Prometheus 지표 노출 — PLAN.md 대로 추후.
- RDS 알람(CPU/커넥션/메모리/스토리지) — infra.md 참조 설계엔 있으나 핵심 5개엔 제외. 필요 시 후속.
- 대시보드(CloudWatch Dashboard) — 알람 우선. 추후.

## 순차 진행 체크리스트

### 사전 준비
- [x] AWS CLI 자격증명 확인 (`aws sts get-caller-identity`)
- [x] 대상 `INSTANCE_ID` 확인 (`i-0d131fd9dd2bc8eee`)
- [x] 알람 수신용 별도 이메일 주소 결정 (개인 상용 주소 아님)
- [x] EC2 에서 CloudWatch Agent 실제 구동 확인 (`amazon-cloudwatch-agent-ctl -a status` = running)
- [x] 로그 그룹 `/baseball/app` 생성 + **로그 실유입 확인** (스트림 `i-0d131fd9dd2bc8eee` 생성됨)
- [x] cwagent 가 `server.log` 읽도록 `/home/ubuntu` traverse 권한 확보 (`usermod -aG ubuntu cwagent` — 위 권한 버그 참조)

### 스크립트 작성
- [x] `deploy/monitoring/setup-cloudwatch.sh` 작성 (파라미터 파싱 + `--dry-run` + `set -euo pipefail`)
- [x] SNS 토픽 `baseball-alarms` 생성 + `ALARM_EMAIL` 구독 로직
- [x] Metric Filter 2개(`error_count`, `slow_count`) 생성 로직
- [x] 알람 5개 생성 로직 (SNS ARN 액션 연결)
- [x] `deploy/monitoring/README.md` 작성 (실행법 + 임계치 표 + 튜닝/구독확인 안내)

### 실행/검증
- [x] `--dry-run` 으로 실행 CLI 리뷰
- [x] 실제 실행 (`ALARM_EMAIL=... INSTANCE_ID=... ./setup-cloudwatch.sh`) — 첫 실행은 로그 그룹 부재로 `put-metric-filter` 실패 → 스크립트에 `create-log-group` 보강 후 재실행 성공
- [x] SNS 구독 확인 이메일 클릭 — `Subscription confirmed` (구독 ARN `...:baseball-alarms:d2a1c21d-...`). 이제 알람 이메일 수신됨
- [x] `aws cloudwatch describe-alarms` 5개 확인 — 5개 모두 확인됨(`baseball-error-spike/slow-requests/mem-high/cpu-credit-low/cpu-high`)
- [ ] `error_count` 실동작 확인 — **다음 STEP(부하 테스트)에서 검증 예정**. 잘못된 추측은 이제 `REJECTED` 라 안 잡힘 → 진짜 서버 예외 또는 `slow_count` 로 대체 검증
- [x] 커밋 (실이메일 미포함 확인 후) — `1be3c9d feat: CloudWatch 모니터링 셋업 스크립트 + 문서` 커밋·푸시 완료

### 후속(범위 밖, 별도 STEP)
- [ ] 부하 테스트로 알람 실동작 검증
- [ ] 베이스라인 확보 후 임계치 튜닝
- [ ] (선택) RDS 알람, 대시보드 추가
