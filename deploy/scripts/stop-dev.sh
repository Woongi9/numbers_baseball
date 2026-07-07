#!/usr/bin/env bash
# stop-dev.sh — baseball-dev 서비스 정지 (stop.sh의 dev 버전, STEP 13-B)
#   - prod(stop.sh/baseball)와 완전히 분리된 서비스명을 다뤄 서로 간섭하지 않는다.
set -euo pipefail

PROJECT_ROOT="/home/ubuntu"
DEPLOY_LOG="$PROJECT_ROOT/logs/deploy-dev.log"
SERVICE="baseball-dev"

mkdir -p "$PROJECT_ROOT/logs"
TIME_NOW=$(date +%c)

if systemctl is-active --quiet "$SERVICE"; then
  echo "$TIME_NOW > 실행중인 $SERVICE 서비스 정지" >> "$DEPLOY_LOG"
  sudo systemctl stop "$SERVICE"
else
  echo "$TIME_NOW > 현재 실행중인 $SERVICE 서비스가 없습니다" >> "$DEPLOY_LOG"
fi
