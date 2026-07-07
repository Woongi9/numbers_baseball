# 숫자야구 챗봇 — AWS 배포 런북 (콘솔 단계별)

> `infra.md` 전략 실행판. 이 문서의 콘솔 단계는 **네가 직접** 수행하고, 레포에 추가된 코드/설정 파일(`.github/workflows/deploy.yml`, `application-prod.yml`, `deploy/*`)은 이미 준비되어 있다.
> 리전은 **서울(`ap-northeast-2`)** 기준. HTTPS는 **기존 DuckDNS + Let's Encrypt 재사용**.

## 0. 전체 흐름 (한눈에)

```
① RDS 생성(Single-AZ)  ──┐
② EC2 생성 + EIP + 보안그룹 │  AWS 콘솔에서 네가 수행
③ 서버 셋업(JDK·systemd·Nginx·certbot)
④ GitHub Secrets 등록     │
⑤ main push → 자동 배포    ┘  여기부터 코드/워크플로가 자동 처리
⑥ 카카오 오픈빌더 스킬 URL 연결
⑦ CloudWatch(에이전트·로그·알람) 관측
```

순서가 중요하다. **RDS → EC2 → 보안그룹 연결 → 서버 셋업 → 배포 → 모니터링.** DB가 없으면 앱이 못 뜨고, 보안그룹이 안 열리면 EC2가 RDS에 못 붙는다.

---

## 1. RDS 생성 (MySQL, db.t4g.micro, Single-AZ)

콘솔 → RDS → **데이터베이스 생성**

| 항목 | 값                      | 이유 |
|------|------------------------|------|
| 생성 방식 | 표준 생성                  | 세부 제어 필요 |
| 엔진 | MySQL 8.0              | 로컬 docker-compose와 동일 버전(8.0 + utf8mb4) |
| 템플릿 | 프리티어 또는 개발/테스트         | 과프로비저닝 금지 |
| 인스턴스 | **db.t4g.micro**       | 이 트래픽엔 충분, 버스터블이 idle 패턴에 적합 |
| 다중 AZ | **사용 안 함(Single-AZ)**  | 초기 비용 절감. 안정화 후 전환 검토 |
| 스토리지 | gp3 20GB, **자동 확장 ON** | gp3가 저렴/일관. ⚠️ 스토리지는 축소 불가 |
| 퍼블릭 액세스 | **아니요(No)**            | 외부 노출 금지. 점검은 EC2 경유 SSH 터널 |
| 초기 DB 이름 | `rds-numbers-baseball` | 앱이 접속할 스키마 |
| 마스터 사용자 | `woong`            | `baseball.env`의 DB_USERNAME과 일치 |
| 마스터 암호 | 강한 암호                  | `baseball.env`의 DB_PASSWORD에만 보관 |
| 자동 백업 | 보존 **7일**              | 복구 지점 확보 |

> **보안그룹은 EC2를 만든 뒤** "EC2 보안그룹만 3306 허용"으로 다시 설정한다(아래 2-④). 지금은 임시로 두고 나중에 좁힌다.

생성 후 **엔드포인트**(예: `baseball.xxxx.ap-northeast-2.rds.amazonaws.com`)를 메모 → `baseball.env`의 `DB_URL`에 넣는다.

---

## 2. EC2 생성 + EIP + 보안그룹

### 2-① 인스턴스 생성
콘솔 → EC2 → **인스턴스 시작**

| 항목 | 값 | 이유 |
|------|----|------|
| AMI | **Ubuntu Server 24.04 LTS (ARM64)** | openjdk-21 기본 제공, ARM=Graviton |
| 아키텍처 | **64-bit (Arm)** | t4g 계열은 ARM. ⚠️ x86 AMI 고르면 안 됨 |
| 인스턴스 타입 | **t4g.small** (2 vCPU/2GB) | JVM에 1GB는 빠듯 → 2GB |
| 키 페어 | 새로 생성 또는 기존 | SSH 접속용(아래 배포키와 별개여도 됨) |
| 스토리지 | gp3 20GB | |

### 2-② Elastic IP 할당 (필수)
EC2 → **탄력적 IP** → 할당 → 방금 만든 인스턴스에 **연결**.
> 스케일업(중지→타입변경→시작) 시 자동 퍼블릭 IP가 바뀌어 도메인 연결이 깨진다. **EIP로 고정**해야 DuckDNS A레코드가 유지된다.

### 2-③ EC2 보안그룹 (인바운드)
| 포트 | 소스 | 이유 |
|------|------|------|
| 443 | `0.0.0.0/0` | 카카오 HTTPS 인입 |
| 80 | `0.0.0.0/0` | HTTPS 리다이렉트 + certbot 인증(HTTP-01) |
| 22 | `0.0.0.0/0` (key-only) | ⚠️ GitHub Actions 러너 IP가 유동적이라 좁히기 어려움. **키 인증만 허용**으로 방어(아래 3-⑥). 추후 SSM/OIDC로 전환해 22번을 닫는 게 목표 |
| 8080 | 열지 않음 | Nginx만 localhost로 프록시 |

### 2-④ RDS 보안그룹 좁히기 (지금 수행)
RDS의 보안그룹 인바운드를 **3306 / 소스 = EC2 보안그룹 ID**로 설정. CIDR 전체 개방 금지.
> 이렇게 하면 EC2에서만 DB에 접속 가능하다. 보안그룹을 소스로 지정하면 EC2 IP가 바뀌어도 규칙이 유지된다.

---

## 3. 서버 셋업 (EC2에 SSH 접속 후)

```bash
ssh -i <키페어>.pem ubuntu@<EIP>
```

### 3-① 패키지 / JDK 21 / Nginx
```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk nginx
java -version          # 21 확인
mkdir -p /home/ubuntu/logs /home/ubuntu/deploy
```

### 3-② DB 비밀 파일 (서버에만)
```bash
# 레포의 deploy/baseball.env.example 내용을 참고해 작성
nano /home/ubuntu/baseball.env       # DB_URL/USERNAME/PASSWORD 채우기 (RDS 엔드포인트)
chmod 600 /home/ubuntu/baseball.env  # 소유자만 읽기
```
> 비밀은 **CI/코드가 아니라 서버에만** 둔다. 그래서 `application-prod.yml`은 `${DB_URL}` 환경변수만 참조한다.

### 3-③ systemd 등록
레포의 `deploy/baseball.service`를 서버로 복사(또는 내용 붙여넣기) 후:
```bash
sudo cp deploy/baseball.service /etc/systemd/system/baseball.service
sudo systemctl daemon-reload
sudo systemctl enable baseball     # 부팅 시 자동 기동(아직 jar 없으니 start는 첫 배포 후)
```

### 3-④ sudoers NOPASSWD (배포가 비번 없이 재시작)
```bash
sudo visudo -f /etc/sudoers.d/baseball-deploy
```
아래 한 줄 입력(재시작 명령만 한정 허용 — 최소 권한):
```
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart baseball, /usr/bin/systemctl start baseball, /usr/bin/systemctl stop baseball
```

### 3-⑤ Nginx + HTTPS(DuckDNS)
1. DuckDNS 사이트에서 기존 서브도메인의 IP를 **새 EIP로 갱신**(A레코드).
2. Nginx 설정 배치(레포 `deploy/nginx-baseball.conf`의 `CHANGE-ME.duckdns.org`를 실제 도메인으로 치환):
```bash
sudo cp deploy/nginx-baseball.conf /etc/nginx/sites-available/baseball
sudo ln -sf /etc/nginx/sites-available/baseball /etc/nginx/sites-enabled/baseball
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```
3. 인증서 발급(certbot이 ssl 경로를 자동 채움):
```bash
sudo snap install --classic certbot
sudo ln -sf /snap/bin/certbot /usr/bin/certbot
sudo certbot --nginx -d <도메인>.duckdns.org
```

### 3-⑥ SSH 키 인증만 허용 (22번 개방 방어)
```bash
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```

---

## 4. GitHub Secrets + 배포 전용 키

### 4-① 배포 전용 SSH 키 생성 (로컬에서)
```bash
ssh-keygen -t ed25519 -f deploy_key -C "github-actions-deploy" -N ""
# 공개키를 EC2에 등록
ssh-copy-id -i deploy_key.pub ubuntu@<EIP>      # 또는 authorized_keys에 수동 추가
```
> 개인키 재사용 금지. **배포 전용 키**를 따로 만들어 유출 시 영향 최소화.

### 4-② GitHub → Settings → Secrets and variables → Actions
| 이름 | 값 |
|------|----|
| `EC2_HOST` | EIP 또는 `<도메인>.duckdns.org` |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | `deploy_key` (개인키) 파일 **전체 내용** |

---

## 5. 첫 배포 + 스키마 부트스트랩

`application-prod.yml`은 `ddl-auto: validate`로 되어 있다 → **스키마가 없으면 앱이 안 뜬다**(첫 배포 헬스체크 실패 → 롤백).
첫 배포 전에 **스키마를 1회 만들어야** 한다. 둘 중 하나:

- **방법 A(권장, 간단):** 잠깐만 `validate` → `update`로 바꿔 첫 기동으로 테이블 생성 후 다시 `validate`로 되돌린다.
- **방법 B:** EC2에서 RDS로 SSH 터널을 열고 DDL을 수동 실행한다.

그 다음:
```bash
git add -A && git commit -m "infra: AWS 배포 파이프라인" && git push origin main
```
→ GitHub Actions가 자동으로 `test → bootJar(prod) → scp → restart → 헬스체크`를 수행한다.
Actions 탭에서 초록불 + `deploy ok` 로그 확인.

수동 첫 기동이 필요하면 EC2에서:
```bash
sudo systemctl start baseball
curl -fsS http://localhost:8080/actuator/health   # {"status":"UP"} 확인
```

---

## 6. 카카오 오픈빌더 연결

오픈빌더 → 스킬 → 스킬 URL: `https://<도메인>.duckdns.org/skill/play` (POST)
외부에서 헬스 확인:
```bash
curl -i https://<도메인>.duckdns.org/skill/play -X POST -H 'Content-Type: application/json' -d '{}'
```

---

## 7. CloudWatch (관측 + 알람)

### 7-① EC2에 IAM 역할(에이전트 권한) 부여
EC2 → 인스턴스 → 작업 → 보안 → **IAM 역할 수정** → 역할 생성/연결.
- 신뢰 주체: EC2
- 정책: **`CloudWatchAgentServerPolicy`** (관리형) 연결
> 이 역할이 있어야 에이전트가 지표/로그를 CloudWatch로 보낼 수 있다.

### 7-② CloudWatch Agent 설치 + 설정
```bash
# ARM64용 에이전트
wget https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/arm64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb
# 레포의 deploy/amazon-cloudwatch-agent.json 적용
sudo cp deploy/amazon-cloudwatch-agent.json /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s
```
이제 `CWAgent` 네임스페이스에 **mem_used_percent / disk_used_percent**가, 로그그룹 **`/baseball/app`** 에 앱 로그가 올라간다.

### 7-③ 앱 로그 → 지표화 (Metric Filter)
CloudWatch → 로그 그룹 `/baseball/app` → **지표 필터 생성**

| 필터 패턴 | 지표 이름 | 의미 |
|-----------|-----------|------|
| `"status=ERROR"` | `error_count` (네임스페이스 `Baseball/App`) | 9-F LogTrace의 에러 발생 수 |
| `"slow=true"` | `slow_count` (네임스페이스 `Baseball/App`) | 5초 근접 지연 발생 수 |

> LogTraceAspect가 남기는 `phase=END ... status=... elapsedMs=... slow=...` 로그를 패턴으로 카운트한다.

### 7-④ SNS 주제 + 구독
CloudWatch → 또는 SNS → 주제 `baseball-alerts` 생성 → 이메일 구독(확인 메일 클릭).

### 7-⑤ 알람 생성 (시작 임계치 — baseline 보고 튜닝)
| 알람 | 지표 / 조건 | 액션 |
|------|-------------|------|
| EC2 CPU 高 | `AWS/EC2 CPUUtilization > 70%` 10분 | 부하 추세 점검 |
| EC2 크레딧 고갈 | `AWS/EC2 CPUCreditBalance` 0 근접 | m 계열 전환 검토 |
| EC2 메모리 高 | `CWAgent mem_used_percent > 85%` 10분 | EC2 한 단계 ↑ |
| 디스크 부족 | `CWAgent disk_used_percent > 80%` | 볼륨 확장 |
| 앱 에러율 | `Baseball/App error_count > N`/분 | traceId로 추적 |
| 앱 지연 | `Baseball/App slow_count > N`/분 | 원인(앱 vs DB) 구분 |
| RDS CPU 高 | `AWS/RDS CPUUtilization > 80%` 10분 | DB 스케일업 |
| RDS 커넥션 高 | `AWS/RDS DatabaseConnections` max 근접 | 풀/인스턴스 점검 |
| RDS 메모리 低 | `AWS/RDS FreeableMemory` 지속 하락 | DB 한 단계 ↑ |
| RDS 스토리지 低 | `AWS/RDS FreeStorageSpace < 임계` | 스토리지 확장 |

모든 알람의 액션 → SNS `baseball-alerts`.

---

## 8. 배포 후 검증 체크리스트

- [ ] `curl https://<도메인>.duckdns.org/skill/play` 200 응답
- [ ] `systemctl status baseball` active(running)
- [ ] `/actuator/health` → `{"status":"UP"}` (DB 연결 포함)
- [ ] GitHub Actions 초록불 + `deploy ok`
- [ ] 일부러 테스트 깨뜨려 push → **배포 차단**되는지(품질 게이트 동작) 확인
- [ ] CloudWatch에 `mem_used_percent` 지표 들어옴
- [ ] 로그그룹 `/baseball/app`에 로그 적재됨
- [ ] SNS 구독 이메일 확인 완료
- [ ] 알람 1개 강제 트리거 → 메일 수신 확인

---

## 9. 롤백 / 트러블슈팅

| 증상 | 원인 후보 | 대응 |
|------|-----------|------|
| 배포가 health 단계에서 실패+롤백 | 스키마 없음(validate)·DB 접속 실패·포트 | `journalctl -u baseball -n 100`, `baseball.env` 확인, 5장 스키마 부트스트랩 |
| EC2→RDS 접속 안 됨 | RDS 보안그룹이 EC2 SG 미허용 | 2-④ 재확인 |
| HTTPS 안 열림 | certbot 미발급·80 차단 | `sudo certbot --nginx`, SG 80 확인 |
| 메모리 알람 잦음/Full GC | 힙 부족 | 힙 키우기보다 **t4g.medium(4GB)로 인스턴스 ↑** |
| 5초 근접 지연 | 앱 vs DB 구분 | RDS Latency 동반 상승 → DB↑, 아니면 앱/JVM↑ |

> 단일 인스턴스라 재시작 = 수 초 다운타임. 무중단이 필요해지면 ALB+ASG 블루/그린, RDS Read Replica로 확장(추후). 22번 SSH는 운영 안정화 후 **SSM Session Manager + OIDC**로 옮겨 닫는다.

---

## 10. [STEP 13-B] dev 인스턴스(9090) 추가 설정

> 새 EC2/RDS 없이 **같은 서버·같은 RDS 인스턴스 위에** dev를 얹는다. 아래는 레포에 이미 준비된 코드/설정 파일(`application-dev.yml`, `deploy/baseball-dev.service`, `deploy/scripts/{start,stop}-dev.sh`, `deploy/nginx-baseball.conf`의 dev 블록, `.github/workflows/deploy-dev.yml`)을 서버·DNS·GitHub에 실제로 반영하는 콘솔/CLI 단계다. PLAN.md STEP 13-B 참고.

### 10-① RDS에 dev 스키마 추가 (기존 RDS, 새 인스턴스 아님)
EC2에서 RDS로 접속(2-④에서 이미 열어둔 EC2→RDS 3306 경로 재사용):
```bash
mysql -h <RDS엔드포인트> -u <마스터계정> -p
```
```sql
CREATE DATABASE baseball_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 기존 계정을 그대로 쓰면 GRANT만, 전용 계정을 쓰려면 CREATE USER부터.
GRANT ALL PRIVILEGES ON baseball_dev.* TO '<계정>'@'%';
FLUSH PRIVILEGES;
```

### 10-② dev 비밀 파일 생성 (EC2 `/home/ubuntu/baseball-dev.env`)
`baseball.env`(prod)와 완전히 분리된 파일. `DB_URL`의 스키마명만 `baseball_dev`로 다르고 나머지는 `deploy/baseball.env.example` 형식과 동일:
```bash
cat <<'EOF' | sudo tee /home/ubuntu/baseball-dev.env > /dev/null
DB_URL=jdbc:mysql://<RDS엔드포인트>:3306/baseball_dev?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useUnicode=true
DB_USERNAME=<계정>
DB_PASSWORD=<비밀번호>
EOF
sudo chmod 600 /home/ubuntu/baseball-dev.env
```

### 10-③ systemd 유닛 배치
```bash
sudo cp deploy/baseball-dev.service /etc/systemd/system/baseball-dev.service
sudo systemctl daemon-reload
sudo systemctl enable baseball-dev   # 아직 jar가 없으면 start는 첫 배포(Actions) 이후에
```

### 10-④ sudoers — baseball-dev NOPASSWD 추가
기존 `baseball` 3종과 같은 패턴으로, `baseball-dev`도 별도 등록(합쳐도 되고 별도 파일로 둬도 됨):
```bash
SC=$(which systemctl)
sudo tee -a /etc/sudoers.d/baseball-deploy > /dev/null <<EOF
ubuntu ALL=(root) NOPASSWD: $SC start baseball-dev, $SC stop baseball-dev, $SC restart baseball-dev
EOF
sudo chmod 440 /etc/sudoers.d/baseball-deploy
sudo visudo -c   # "parsed OK" 확인
```

### 10-⑤ Nginx — dev 서버 블록 반영 + 인증서
`deploy/nginx-baseball.conf`에 dev 블록이 이미 추가돼 있다. 서버에 반영하기 전에 **prod(numbers-baseball.com)가 지금 실제로 어떤 방식으로 인증서를 받고 있는지**(certbot Let's Encrypt인지 Cloudflare Origin CA인지) 먼저 확인하고 dev 블록의 `ssl_certificate` 경로/발급 명령을 그 방식에 맞춘다.
```bash
sudo cp deploy/nginx-baseball.conf /etc/nginx/sites-available/baseball
# (또는 서버에 이미 다른 파일명으로 배치돼 있다면 그 파일에 dev server 블록만 옮겨 붙인다)
sudo nginx -t && sudo systemctl reload nginx
```

### 10-⑥ Cloudflare DNS — dev 서브도메인 추가
Cloudflare 대시보드 → `numbers-baseball.com` → DNS → 레코드 추가:

| 타입 | 이름 | 값 | 프록시 상태 |
|------|------|-----|-------------|
| A (또는 기존 A와 동일 방식) | `dev` | EIP | 기존 `numbers-baseball.com` 레코드와 동일하게(Proxied/DNS only) 맞춘다 |

### 10-⑦ GitHub — `develop` 브랜치 + Secrets 확인
```bash
git checkout -b develop
git push -u origin develop
```
- `.github/workflows/deploy-dev.yml`은 기존 Secrets(`EC2_HOST`/`EC2_USER`/`EC2_SSH_KEY`)를 그대로 재사용한다(같은 서버라 새 Secret 불필요).
- `develop` push → Actions "Deploy Dev" 워크플로가 도는지 확인.

### 10-⑧ 배포 후 검증
- [ ] `curl https://dev.numbers-baseball.com/actuator/health` → `{"status":"UP"}`
- [ ] `systemctl status baseball` (prod)와 `systemctl status baseball-dev` (dev) 둘 다 active — 서로 영향 없는지 확인
- [ ] `free -h`로 두 JVM 동시 구동 시 메모리 여유 확인(압박 보이면 STEP 13-B 결정사항대로 힙↓ 또는 t4g.medium 검토)
- [ ] `main` push(prod 배포)와 `develop` push(dev 배포)를 각각 트리거해 두 워크플로가 서로 취소/간섭하지 않는지 확인
