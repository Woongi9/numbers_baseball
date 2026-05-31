#!/usr/bin/env bash
# 코드 수정 후 재배포: 빌드 → 업로드 → 재시작
# 사용법: ./deploy/redeploy.sh <ssh-key경로> <서버IP>
#   예) ./deploy/redeploy.sh ~/Downloads/ssh-key.key 140.238.0.1
set -euo pipefail

KEY="${1:?ssh key 경로를 넘겨주세요}"
HOST="${2:?서버 IP를 넘겨주세요}"
USER="ubuntu"
JAR="build/libs/baseball.jar"

echo "==> 1/3 빌드"
./gradlew clean bootJar

echo "==> 2/3 업로드 ($JAR → $USER@$HOST)"
scp -i "$KEY" "$JAR" "$USER@$HOST:/home/$USER/"

echo "==> 3/3 서비스 재시작"
ssh -i "$KEY" "$USER@$HOST" "sudo systemctl restart baseball && sleep 2 && systemctl is-active baseball"

echo "==> 완료. 로그: ssh -i $KEY $USER@$HOST 'journalctl -u baseball -f'"
