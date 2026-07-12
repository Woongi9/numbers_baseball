#!/usr/bin/env bash
#
# CloudWatch 모니터링 셋업 — SNS 토픽/구독 + Metric Filter 2개 + 알람 5개.
# 멱등: put-metric-filter/put-metric-alarm 은 upsert, create-topic 은 같은 이름=같은 ARN.
# 이메일은 하드코딩 금지 — ALARM_EMAIL 로 주입(레포 노출 방지, 개인 상용 주소 아닌 별도 주소).
#
# 사용:
#   ALARM_EMAIL=you@example.com INSTANCE_ID=i-xxxx ./setup-cloudwatch.sh
#   ALARM_EMAIL=you@example.com INSTANCE_ID=i-xxxx ./setup-cloudwatch.sh --dry-run
#
set -euo pipefail

ALARM_EMAIL="${ALARM_EMAIL:-}"
INSTANCE_ID="${INSTANCE_ID:-}"
REGION="${REGION:-ap-northeast-2}"
LOG_GROUP="${LOG_GROUP:-/baseball/app}"
NAMESPACE="Baseball/App"
TOPIC_NAME="baseball-alarms"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

if [[ -z "$ALARM_EMAIL" || -z "$INSTANCE_ID" ]]; then
  echo "usage: ALARM_EMAIL=<email> INSTANCE_ID=<i-xxxx> $0 [--dry-run]" >&2
  echo "  ALARM_EMAIL, INSTANCE_ID 는 필수." >&2
  exit 1
fi

# dry-run 이면 aws 대신 명령을 출력만 한다.
run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf 'aws'; printf ' %q' "$@"; printf '\n'
  else
    aws "$@"
  fi
}

echo "== SNS 토픽 생성/조회 =="
if [[ "$DRY_RUN" == "1" ]]; then
  run sns create-topic --name "$TOPIC_NAME" --region "$REGION"
  TOPIC_ARN="arn:aws:sns:${REGION}:ACCOUNT_ID:${TOPIC_NAME}"  # dry-run 자리표시자
else
  TOPIC_ARN=$(aws sns create-topic --name "$TOPIC_NAME" --region "$REGION" --output text --query 'TopicArn')
fi
echo "TOPIC_ARN=$TOPIC_ARN"

echo "== 이메일 구독(미확인 시 확인 메일 발송) =="
run sns subscribe --topic-arn "$TOPIC_ARN" --protocol email --notification-endpoint "$ALARM_EMAIL" --region "$REGION"

echo "== 로그 그룹 확인/생성 =="
# Agent 가 아직 로그를 안 보냈으면 그룹이 없어 put-metric-filter 가 실패한다. 멱등 생성.
if [[ "$DRY_RUN" == "1" ]]; then
  run logs create-log-group --log-group-name "$LOG_GROUP" --region "$REGION"
else
  aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$REGION" 2>/dev/null || true
fi

echo "== Metric Filter 2개 =="
run logs put-metric-filter \
  --log-group-name "$LOG_GROUP" \
  --filter-name "error_count" \
  --filter-pattern '"status=ERROR"' \
  --metric-transformations metricName=error_count,metricNamespace="$NAMESPACE",metricValue=1,defaultValue=0 \
  --region "$REGION"

run logs put-metric-filter \
  --log-group-name "$LOG_GROUP" \
  --filter-name "slow_count" \
  --filter-pattern '"slow=true"' \
  --metric-transformations metricName=slow_count,metricNamespace="$NAMESPACE",metricValue=1,defaultValue=0 \
  --region "$REGION"

echo "== 알람 5개 =="
# 1) 앱 에러 급증 — 정상 유저 입력 예외도 status=ERROR 라 스파이크만: 5분 합 > 10
run cloudwatch put-metric-alarm \
  --alarm-name "baseball-error-spike" \
  --alarm-description "앱 에러 급증 (5분 합 error_count > 10)" \
  --namespace "$NAMESPACE" --metric-name error_count \
  --statistic Sum --period 300 --evaluation-periods 1 \
  --threshold 10 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN" --region "$REGION"

# 2) 앱 지연 — slow=true(3초 초과), 카카오 5초 타임아웃 직전 신호: 5분 합 >= 3
run cloudwatch put-metric-alarm \
  --alarm-name "baseball-slow-requests" \
  --alarm-description "앱 지연 (5분 합 slow_count >= 3)" \
  --namespace "$NAMESPACE" --metric-name slow_count \
  --statistic Sum --period 300 --evaluation-periods 1 \
  --threshold 3 --comparison-operator GreaterThanOrEqualToThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN" --region "$REGION"

# 3) 메모리 — t4g.small 2GB 에 prod+dev JVM 2개, 10분 지속만: Avg > 85%
run cloudwatch put-metric-alarm \
  --alarm-name "baseball-mem-high" \
  --alarm-description "메모리 사용률 높음 (10분 평균 > 85%)" \
  --namespace "CWAgent" --metric-name mem_used_percent \
  --dimensions Name=InstanceId,Value="$INSTANCE_ID" \
  --statistic Average --period 300 --evaluation-periods 2 \
  --threshold 85 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN" --region "$REGION"

# 4) CPU 크레딧 고갈 — 버스터블 스로틀 전조: Avg < 60
run cloudwatch put-metric-alarm \
  --alarm-name "baseball-cpu-credit-low" \
  --alarm-description "CPU 크레딧 잔량 낮음 (10분 평균 < 60)" \
  --namespace "AWS/EC2" --metric-name CPUCreditBalance \
  --dimensions Name=InstanceId,Value="$INSTANCE_ID" \
  --statistic Average --period 300 --evaluation-periods 2 \
  --threshold 60 --comparison-operator LessThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN" --region "$REGION"

# 5) CPU 사용률 — 크레딧 고갈 전 추세 조기 포착: Avg > 70%
run cloudwatch put-metric-alarm \
  --alarm-name "baseball-cpu-high" \
  --alarm-description "CPU 사용률 높음 (10분 평균 > 70%)" \
  --namespace "AWS/EC2" --metric-name CPUUtilization \
  --dimensions Name=InstanceId,Value="$INSTANCE_ID" \
  --statistic Average --period 300 --evaluation-periods 2 \
  --threshold 70 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN" --region "$REGION"

echo "== 완료 =="
echo "구독 확인 이메일($ALARM_EMAIL)의 Confirm subscription 링크를 눌러야 알람 수신됨."
