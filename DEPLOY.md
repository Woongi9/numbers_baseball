# 숫자야구 챗봇 — Oracle Cloud Always Free 배포 가이드

> 목표: 로컬에서 만든 Spring Boot JAR을 OCI 무료 VM에 올려 **HTTPS로 외부 공개** → 카카오 오픈빌더 스킬 URL로 연결.
> 핵심: JAR은 아키텍처 무관(JVM)이라 ARM 인스턴스에서도 그대로 돈다. Docker 없이 `java -jar`로 충분.

---

## 0. 준비물 체크

- [ ] OCI 가입용: 해외 결제 가능한 카드(본인 인증용, 과금 아님), 휴대폰
- [ ] 로컬에 프로젝트 (`./gradlew bootJar` 가능)
- [ ] (HTTPS용) 도메인 — 없으면 **DuckDNS 무료 서브도메인** 사용 (8단계 참고)

---

## 1. OCI 계정 가입

1. https://www.oracle.com/cloud/free/ → "Start for free"
2. **홈 리전(Home Region)을 `South Korea Central (Chuncheon)` 또는 `Seoul`로 선택** — 가입 후 변경 불가. 카카오 서버와 가까워 응답 지연이 줄어든다.
3. 카드 인증 완료 (Always Free 리소스는 과금되지 않음)

---

## 2. ARM(A1) 인스턴스 생성

콘솔 → Compute → Instances → **Create Instance**

| 항목 | 값 |
|------|-----|
| Image | **Ubuntu 24.04** (또는 22.04) |
| Shape | **VM.Standard.A1.Flex** (ARM) → OCPU 2, 메모리 12GB 정도면 충분 (무료 한도: 4 OCPU/24GB) |
| SSH Key | "Generate a key pair for me" → **개인키(.key) 반드시 다운로드** |
| Networking | 새 VCN 자동 생성, **Assign public IP** 체크 |

> ⚠️ **ARM 인스턴스 "Out of capacity" 에러**가 흔하다. 뜨면 잠시 후/다른 가용 도메인(AD)으로 재시도. 정 안 되면 임시로 AMD(VM.Standard.E2.1.Micro, 1GB)로 시작 가능하나 메모리가 빠듯하다.

생성되면 **Public IP**를 메모해 둔다. (예: `140.238.x.x`)

---

## 3. 네트워크 포트 개방 ⚠️ (가장 흔한 함정)

OCI는 **두 군데**에서 막혀 있어 둘 다 열어야 한다. 하나만 열면 "접속은 되는데 웹이 안 뜨는" 상황이 된다.

### (A) Security List (VCN 방화벽)
콘솔 → Networking → Virtual Cloud Networks → 해당 VCN → Subnet → Security List → **Add Ingress Rules**

| Source CIDR | Port | 용도 |
|-------------|------|------|
| 0.0.0.0/0 | 80 | HTTP (Certbot 인증 + 리다이렉트) |
| 0.0.0.0/0 | 443 | HTTPS |

(22번 SSH는 기본 열려 있음)

### (B) 인스턴스 내부 방화벽
Ubuntu 이미지는 iptables 규칙이 기본 적용돼 있다. SSH 접속 후 (4단계) 아래 실행:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

---

## 4. SSH 접속 + Java 21 설치

```bash
# 로컬에서 (다운로드한 개인키 권한 설정)
chmod 600 ~/Downloads/ssh-key.key
ssh -i ~/Downloads/ssh-key.key ubuntu@<PUBLIC_IP>

# 서버에서 Java 21 설치 (ARM/x86 자동 매칭)
sudo apt update
sudo apt install -y openjdk-21-jre-headless
java -version   # 21 확인
```

---

## 5. JAR 빌드 & 업로드

```bash
# 로컬에서 빌드 (파일명 baseball.jar 로 고정해 둠)
./gradlew clean bootJar
# → build/libs/baseball.jar 생성

# 서버로 전송
scp -i ~/Downloads/ssh-key.key build/libs/baseball.jar ubuntu@<PUBLIC_IP>:/home/ubuntu/

# 서버에서 임시 실행 테스트
ssh -i ~/Downloads/ssh-key.key ubuntu@<PUBLIC_IP>
java -jar /home/ubuntu/baseball.jar
# → "Tomcat started on port 8080" 확인 후 Ctrl+C
```

> H2 파일 모드라 실행 디렉토리에 `data/baseball.mv.db`가 생긴다. 6단계 systemd의 WorkingDirectory가 이 위치를 결정한다.

---

## 6. systemd 서비스 등록 (상시 가동 + 자동 재시작)

서버에서 `/etc/systemd/system/baseball.service` 생성 (deploy/baseball.service 내용 사용):

```bash
sudo nano /etc/systemd/system/baseball.service   # 아래 내용 붙여넣기
sudo systemctl daemon-reload
sudo systemctl enable --now baseball
sudo systemctl status baseball        # active (running) 확인
curl localhost:8080/skill/play -X POST -H "Content-Type: application/json" \
  -d '{"userRequest":{"utterance":"시작","user":{"id":"u1"}}}'   # 응답 확인
```

> systemd로 등록하면 서버 재부팅·앱 크래시 시 자동 재시작된다. (운영 안정성 — "장애 빠른 복구" 지표)

---

## 7. 도메인 준비 (무료: DuckDNS)

HTTPS 인증서(Let's Encrypt)는 도메인이 필요하다. 도메인이 없으면:

1. https://www.duckdns.org → 로그인 → 서브도메인 생성 (예: `mybaseball`)
2. current ip에 **VM Public IP** 입력 → `mybaseball.duckdns.org` 가 IP를 가리킴

---

## 8. Nginx 리버스 프록시 + HTTPS (Certbot)

```bash
sudo apt install -y nginx
sudo nano /etc/nginx/sites-available/baseball   # deploy/nginx-baseball.conf 내용, server_name 을 본인 도메인으로
sudo ln -s /etc/nginx/sites-available/baseball /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

# HTTPS 인증서 자동 발급 + Nginx 설정 자동 수정 + 자동 갱신 등록
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d mybaseball.duckdns.org
```

Certbot이 80→443 리다이렉트와 인증서 자동 갱신(cron)까지 설정해 준다.

> **더 쉬운 대안 — Caddy**: Nginx+Certbot 대신 Caddy 한 줄(`reverse_proxy localhost:8080`)이면 HTTPS가 자동 발급/갱신된다. 학습용으로 Nginx를 권장하지만, 빠르게 끝내려면 Caddy가 편하다.

---

## 9. 외부 동작 확인

```bash
curl -X POST https://mybaseball.duckdns.org/skill/play \
  -H "Content-Type: application/json" \
  -d '{"userRequest":{"utterance":"시작","user":{"id":"u1"}}}'
```

→ `{"version":"2.0","template":{"outputs":[{"simpleText":{"text":"새 게임을..."}}]}}` 가 나오면 성공.

---

## 10. 카카오 오픈빌더 연결 (STEP 7)

1. 카카오 비즈니스 채널 + 오픈빌더 봇 생성
2. 스킬 등록: URL = `https://mybaseball.duckdns.org/skill/play`
3. 폴백 블록(+ 시작/포기 블록)에 스킬 연결
4. 봇 테스트에서 숫자 입력 → 판정 응답 확인

---

## 11. 재배포 (코드 수정 후)

`deploy/redeploy.sh` 참고. 요약:

```bash
./gradlew clean bootJar
scp -i <key> build/libs/baseball.jar ubuntu@<IP>:/home/ubuntu/
ssh -i <key> ubuntu@<IP> "sudo systemctl restart baseball"
```

---

## 운영 팁

- 로그 보기: `sudo journalctl -u baseball -f`
- 메모리 여유 적은 인스턴스면 JVM 힙 제한: ExecStart에 `-Xmx512m`
- Swagger는 운영에서 끄기: prod 프로파일에 `springdoc.api-docs.enabled: false`
- H2 파일 DB는 단일 인스턴스 전용. 트래픽 커지면 관리형 DB로 전환.
