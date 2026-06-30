#!/usr/bin/env bash
# stop.sh — baseball 서비스 정지 (익숙한 스크립트 흐름 유지 + systemd 위임)
#   - 실제 프로세스 관리는 systemd 가 한다(크래시 자동재시작/부팅 자동기동 보장).
#   - 이 스크립트는 GCP 시절과 동일한 "stop -> start" 배포 흐름을 위한 얇은 래퍼다.
set -euo pipefail

PROJECT_ROOT="/home/ubuntu"
DEPLOY_LOG="$PROJECT_ROOT/logs/deploy.log"
SERVICE="baseball"

mkdir -p "$PROJECT_ROOT/logs"
TIME_NOW=$(date +%c)

# 현재 구동 상태 확인 후 정지.
#   - is-active 는 읽기 전용 → root 불필요(sudo 안 붙임).
#   - stop 만 root 필요 → sudoers NOPASSWD 로 비번 없이 실행.
if systemctl is-active --quiet "$SERVICE"; then
  echo "$TIME_NOW > 실행중인 $SERVICE 서비스 정지" >> "$DEPLOY_LOG"
  sudo systemctl stop "$SERVICE"
else
  echo "$TIME_NOW > 현재 실행중인 $SERVICE 서비스가 없습니다" >> "$DEPLOY_LOG"
fi
