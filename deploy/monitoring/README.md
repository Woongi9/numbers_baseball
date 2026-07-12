# CloudWatch 모니터링

숫자야구 서버(EC2/앱) 이상을 이메일로 조기 감지. 설계: `docs/2026-07-11-cloudwatch-monitoring-design.md`.

## 사전 조건

- EC2 에 IAM 역할 부착(신뢰 주체 EC2) + 정책: `CloudWatchAgentServerPolicy`, `CloudWatchFullAccessV2`, `AmazonSNSFullAccess`.
- CloudWatch Agent 구동 중 — `sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a status` 가 `running`.
- 로그 그룹 `/baseball/app` 존재(Agent 가 로그 보내면 자동 생성).

## 실행

EC2(또는 자격증명 있는 곳)에서:

```bash
# 실행할 CLI 만 출력 (실제 생성 안 함)
ALARM_EMAIL=you@example.com INSTANCE_ID=i-xxxx ./setup-cloudwatch.sh --dry-run

# 실제 생성
ALARM_EMAIL=you@example.com INSTANCE_ID=i-xxxx ./setup-cloudwatch.sh
```

| 파라미터 | 필수 | 기본값 | 비고 |
|----------|------|--------|------|
| `ALARM_EMAIL` | O | (없음) | 알람 수신 이메일. **커밋 파일에 실주소 박지 않음.** |
| `INSTANCE_ID` | O | (없음) | 대상 EC2. |
| `REGION` | X | `ap-northeast-2` | |
| `LOG_GROUP` | X | `/baseball/app` | |

실행 후 **구독 확인 이메일의 Confirm subscription 링크를 눌러야** 알람이 발송된다(최초 1회).

## 알람 5개 (임계치 = 시작값, 베이스라인 확보 후 튜닝)

| 알람 이름 | 지표 | 통계/기간/평가 | 임계 |
|-----------|------|----------------|------|
| `baseball-error-spike` | `Baseball/App/error_count` | Sum / 5분 / 1회 | > 10 |
| `baseball-slow-requests` | `Baseball/App/slow_count` | Sum / 5분 / 1회 | >= 3 |
| `baseball-mem-high` | `CWAgent/mem_used_percent` | Avg / 5분 / 2회(10분) | > 85% |
| `baseball-cpu-credit-low` | `AWS/EC2/CPUCreditBalance` | Avg / 5분 / 2회(10분) | < 60 |
| `baseball-cpu-high` | `AWS/EC2/CPUUtilization` | Avg / 5분 / 2회(10분) | > 70% |

- `error_count` 는 정상 유저 입력 예외(잘못된 추측 등)도 `status=ERROR` 로 남아서 `>10` 스파이크만 잡음.
- `slow_count` 는 3초(`SLOW_THRESHOLD_MS`) 초과 = 카카오 5초 타임아웃 직전 신호.
- 모든 알람 `TreatMissingData=notBreaching`(데이터 없음 = 정상).

## 검증

```bash
aws cloudwatch describe-alarms --region ap-northeast-2 \
  --alarm-name-prefix baseball- --query 'MetricAlarms[].AlarmName'
```
5개 나오면 성공. 이후 잘못된 추측 여러 번 → `error_count` 증가 확인.

## 삭제/재설정

```bash
aws cloudwatch delete-alarms --region ap-northeast-2 --alarm-names \
  baseball-error-spike baseball-slow-requests baseball-mem-high \
  baseball-cpu-credit-low baseball-cpu-high
```
