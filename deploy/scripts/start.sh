#!/usr/bin/env bash
# start.sh — 새 JAR 로 교체 후 systemd 로 baseball 기동 + 헬스체크(실패 시 롤백)
#   배포 산출물 경로
#     - 스테이징(scp 업로드)  : /home/ubuntu/deploy/baseball.jar
#     - 운영(systemd 가 실행) : /home/ubuntu/baseball.jar
#     - 직전 운영본 백업       : /home/ubuntu/baseball.jar.bak
#   비밀(DB 접속정보 등)은 코드/CI 가 아니라 /home/ubuntu/baseball.env 에만 둔다.
set -uo pipefail

PROJECT_ROOT="/home/ubuntu"
LIVE_JAR="$PROJECT_ROOT/baseball.jar"
STAGED_JAR="$PROJECT_ROOT/deploy/baseball.jar"
BACKUP_JAR="$PROJECT_ROOT/baseball.jar.bak"
DEPLOY_LOG="$PROJECT_ROOT/logs/deploy.log"
SERVICE="baseball"
HEALTH_URL="http://localhost:8080/actuator/health"

mkdir -p "$PROJECT_ROOT/logs"
TIME_NOW=$(date +%c)

# 1) 스테이징된 새 JAR 존재 확인
if [ ! -f "$STAGED_JAR" ]; then
  echo "$TIME_NOW > [에러] 스테이징 JAR 없음: $STAGED_JAR" >> "$DEPLOY_LOG"
  exit 1
fi

# 2) 현재 운영본 백업 (롤백 대비)
if [ -f "$LIVE_JAR" ]; then
  cp -f "$LIVE_JAR" "$BACKUP_JAR"
  echo "$TIME_NOW > 현재 JAR 백업: $BACKUP_JAR" >> "$DEPLOY_LOG"
fi

# 3) 새 JAR 을 운영 위치로 교체
mv -f "$STAGED_JAR" "$LIVE_JAR"
echo "$TIME_NOW > 새 JAR 배치 완료: $LIVE_JAR" >> "$DEPLOY_LOG"

# 4) systemd 로 기동 (이후 크래시/재부팅 자동복구는 systemd 가 담당)
echo "$TIME_NOW > $SERVICE 서비스 기동 요청" >> "$DEPLOY_LOG"
sudo systemctl start "$SERVICE"

# 5) 헬스체크: 부팅 시간을 고려해 최대 ~30초(3초 x 10회) 재시도
ok=false
for i in $(seq 1 10); do
  if curl -fsS "$HEALTH_URL" | grep -q '"status":"UP"'; then
    ok=true
    break
  fi
  sleep 3
done

# 6) 실패 시 직전 백업본으로 롤백 (장애 조기 차단)
if [ "$ok" != "true" ]; then
  echo "$TIME_NOW > [실패] 헬스체크 실패 → 롤백 시작" >> "$DEPLOY_LOG"
  if [ -f "$BACKUP_JAR" ]; then
    mv -f "$BACKUP_JAR" "$LIVE_JAR"
    sudo systemctl restart "$SERVICE"
    echo "$TIME_NOW > 이전 JAR 으로 롤백 완료" >> "$DEPLOY_LOG"
  else
    echo "$TIME_NOW > [경고] 백업본 없음 — 수동 점검 필요" >> "$DEPLOY_LOG"
  fi
  exit 1
fi

echo "$TIME_NOW > 배포 성공 (서비스 UP)" >> "$DEPLOY_LOG"

# 7) 카드 썸네일 프리워밍: 공개 도메인으로 각 이미지를 한 번 당겨 Cloudflare 캐시를 채운다.
#    이미지를 Spring 앱이 서빙하므로, 재시작 직후 첫 요청이 콜드 캐시로 실패해 카카오/클라이언트
#    말풍선에 빈 이미지가 굳는 것을 막는다. best-effort — 실패해도 배포는 성공으로 둔다.
WARM_BASE="https://numbers-baseball.com/images"
for img in start strike ball out answer; do
  code=$(curl -fsS -o /dev/null -w '%{http_code}' -m 10 "$WARM_BASE/$img.png" || echo "FAIL")
  echo "$TIME_NOW > 이미지 프리워밍 $img.png -> $code" >> "$DEPLOY_LOG"
done
