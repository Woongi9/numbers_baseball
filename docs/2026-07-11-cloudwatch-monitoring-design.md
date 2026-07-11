# CloudWatch 모니터링 설계

- 날짜: 2026-07-11
- 상태: 설계 확정
- 범위: PLAN.md 6-D 발견2 `모니터링 -> 부하 테스트` 중 **모니터링** (부하 테스트 전 선행)

## 목적

출시 전(실사용자 0) 단계에서 서버/앱 이상을 이메일로 조기 감지한다. 모니터링을 먼저 깔아두면
이후 부하 테스트가 곧 이 대시보드/알람의 실검증이 된다. 앱 코드 변경 없음 — 100% AWS 인프라 작업.

## 전제 (이미 완료된 기반)

- `deploy/amazon-cloudwatch-agent.json` — CloudWatch Agent 가 `mem_used_percent`, `disk_used_percent`
  수집(네임스페이스 `CWAgent`) + `/home/ubuntu/logs/server.log` 를 로그 그룹 `/baseball/app` 로 전송.
- `application-prod.yml` — `logging.file.name: /home/ubuntu/logs/server.log` (Agent 가 tail 하는 파일).
- `LogTraceAspect` — 요청 1건당 한 줄 구조화 로그. `status=ERROR(...)`, `slow=true` 토큰 포함.
- EC2 기본 지표(`AWS/EC2`)는 별도 설정 없이 `CPUUtilization`, `CPUCreditBalance` 제공.

남은 작업 = Metric Filter 2개 + 알람 5개 + SNS 토픽/구독 생성, 그리고 Agent 실제 구동 확인.

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
| `ALARM_EMAIL` | O | (없음) | 알람 수신 이메일. **커밋 파일에 실주소 박지 않음**(레포 노출 방지). jinung9544 아닌 별도 주소. |
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
| 1 | 앱 에러 급증 | `Baseball/App/error_count` | Sum / 5분 / 1회 | > 10 | 잘못된 추측·"게임 없음" 등 **정상 유저 입력 예외도 status=ERROR** 로 남음. `>=1` 은 노이즈 폭탄이라 스파이크만 잡게 5분 합 10 초과. |
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
3. 잘못된 추측 여러 번 발생시켜 `error_count` 증가 확인.
4. Agent 구동 확인: `amazon-cloudwatch-agent-ctl -a status`.
5. 실부하 검증은 다음 STEP(부하 테스트)에서 알람 실동작으로 확인.

## 범위 밖 (YAGNI)

- Micrometer / Prometheus 지표 노출 — PLAN.md 대로 추후.
- RDS 알람(CPU/커넥션/메모리/스토리지) — infra.md 참조 설계엔 있으나 핵심 5개엔 제외. 필요 시 후속.
- 대시보드(CloudWatch Dashboard) — 알람 우선. 추후.

## 순차 진행 체크리스트

### 사전 준비
- [ ] AWS CLI 자격증명 확인 (`aws sts get-caller-identity`)
- [ ] 대상 `INSTANCE_ID` 확인 (`aws ec2 describe-instances`)
- [ ] 알람 수신용 별도 이메일 주소 결정 (jinung9544 아님)
- [ ] EC2 에서 CloudWatch Agent 실제 구동 확인 (`amazon-cloudwatch-agent-ctl -a status`), 미구동 시 설치/기동
- [ ] 로그 그룹 `/baseball/app` 생성됐는지 확인 (Agent 가 로그 보내면 자동 생성)

### 스크립트 작성
- [ ] `deploy/monitoring/setup-cloudwatch.sh` 작성 (파라미터 파싱 + `--dry-run` + `set -euo pipefail`)
- [ ] SNS 토픽 `baseball-alarms` 생성 + `ALARM_EMAIL` 구독 로직
- [ ] Metric Filter 2개(`error_count`, `slow_count`) 생성 로직
- [ ] 알람 5개 생성 로직 (SNS ARN 액션 연결)
- [ ] `deploy/monitoring/README.md` 작성 (실행법 + 임계치 표 + 튜닝/구독확인 안내)

### 실행/검증
- [ ] `--dry-run` 으로 실행 CLI 리뷰
- [ ] 실제 실행 (`ALARM_EMAIL=... INSTANCE_ID=... ./setup-cloudwatch.sh`)
- [ ] SNS 구독 확인 이메일 클릭
- [ ] `aws cloudwatch describe-alarms` 5개 확인
- [ ] 잘못된 추측 발생 → `error_count` 증가 확인
- [ ] 커밋 (실이메일 미포함 확인 후)

### 후속(범위 밖, 별도 STEP)
- [ ] 부하 테스트로 알람 실동작 검증
- [ ] 베이스라인 확보 후 임계치 튜닝
- [ ] (선택) RDS 알람, 대시보드 추가
