# 숫자야구 챗봇 — 인프라 / 배포 / 모니터링 (AWS)

> 카카오 오픈빌더 숫자야구 챗봇의 AWS 배포·운영 문서.
> 전략: **작게 시작(t4g.small + db.t4g.micro, Single-AZ) → CloudWatch로 관측 → 임계 신호 보이면 스케일업.**
> 기존 OCI 무료 배포(Nginx + HTTPS + DuckDNS)에서 AWS로 이전하는 것을 전제로 한다.

---

## 1. 개요

| 항목 | 내용 |
|------|------|
| 서비스 | Kotlin + Spring Boot 3 / JPA, 카카오 스킬 서버 (`POST /skill/play`) |
| DB | MySQL (RDS) |
| 트래픽 가정 | 수만 건/일 (평균 0.5~1 req/s, 피크 ~5~15 req/s) |
| 핵심 제약 | **카카오 5초 타임아웃** — 처리량보다 "꼬리 지연(p99) 안정성"이 중요 |
| 운영 원칙 | 과프로비저닝 금지. 지표 기반으로 수직 확장 |

부하 자체는 작다. 사양을 올리는 이유는 처리량이 아니라 **메모리 여유(JVM GC) + 5초 안에 안정적으로 응답**이다.

---

## 2. 아키텍처 (초기)

```
[카카오 오픈빌더] ──HTTPS──> [EC2: Nginx(443→8080) + Spring Boot(JAR, systemd)]
                                      │ JDBC
                                      ▼
                              [RDS MySQL (Single-AZ)]

   관측: EC2/앱/RDS 지표·로그 → CloudWatch (Agent + Logs) → Alarm → SNS(알림)
```

- 단일 EC2 + 단일 RDS. ALB·오토스케일링·NAT Gateway **사용 안 함**(이 규모에선 비용 낭비).
- HTTPS는 기존처럼 EC2의 Nginx 리버스 프록시 + 인증서로 종단(별도 LB 불필요).

---

## 3. 초기 배포 사양

| 컴포넌트 | 사양 | 이유 | 서울 월비용(근사·On-Demand) |
|----------|------|------|------------------------------|
| **EC2** | `t4g.small` (2 vCPU / 2GB, ARM/Graviton) | Spring Boot 3 JVM에 1GB는 빠듯 → 2GB. ARM이라 x86 t3보다 ~20% 저렴 | ~$15 |
| **RDS** | `db.t4g.micro` (2 vCPU / 1GB), **Single-AZ** | 이 트래픽엔 micro로 충분. 버스터블이 idle-위주 패턴에 적합 | ~$15 |
| **스토리지** | EBS gp3 20GB + RDS gp3 20GB | gp3가 gp2보다 저렴/일관 성능. RDS 스토리지 오토스케일링 ON 권장 | ~$5~6 |
| **합계** | | | **~$35/월** |

> 1년 **Savings Plan(EC2) + RDS Reserved Instance**(선결제 없음)만 걸어도 컴퓨팅이 ~40% 내려가 **~$22~25/월** 수준. 단, **사양이 안정된 뒤** 약정을 걸 것(패밀리 바꾸면 RI 매칭이 깨짐).
> 정확한 금액은 [AWS Pricing Calculator](https://calculator.aws/)에서 `ap-northeast-2`로 확정.

### JVM 메모리 가이드 (t4g.small / 2GB)

- 컨테이너/OS·Nginx가 일부 사용하므로 힙은 보수적으로: `-Xms512m -Xmx1g` 정도에서 시작.
- OOM·잦은 Full GC가 보이면 힙을 늘리기보다 **인스턴스를 t4g.medium(4GB)로 올리는** 게 안전(여유 메모리 = GC 멈춤 감소 = 5초 타임아웃 방어).

---

## 4. 네트워크 / 보안

| 항목 | 설정 |
|------|------|
| **Elastic IP** | EC2에 **반드시 EIP 할당**. 스케일업 시 중지/시작하면 자동 퍼블릭 IP가 바뀌므로(도메인 연결 깨짐) EIP로 고정 |
| 보안그룹(EC2) | 인바운드 443(전체), 22(내 IP만). 8080은 외부 비공개(Nginx만 localhost로 프록시) |
| 보안그룹(RDS) | 3306을 **EC2 보안그룹에서만** 허용(CIDR 전체 개방 금지) |
| 서브넷 | EC2는 퍼블릭 서브넷(EIP). **NAT Gateway 사용 안 함**(시간당+데이터 요금이 작은 서버 비용 1위가 됨) |
| RDS 접근 | 외부 공개 끔(Publicly accessible = No). 운영 점검은 EC2 경유(SSH 터널) |
| HTTPS | 기존 방식 유지(Nginx + 인증서). 도메인은 EIP로 A레코드 |

---

## 5. 배포

### 5-1. 최초 수동 배포 (요약)

1. RDS(`db.t4g.micro`, MySQL, Single-AZ) 생성 → 스키마/계정 준비, 자동 백업(보존 7일 권장) 확인.
2. EC2(`t4g.small`, ARM AMI) 생성 + **EIP 연결** + 보안그룹.
3. JDK 21(ARM) 설치, `baseball.jar` 배포(`/home/ubuntu/baseball.jar`), `systemd`(`baseball.service`)로 상시 가동(기존 OCI 구성 재사용).
4. 운영 프로파일에서 DB 접속을 RDS 엔드포인트로 설정(`-Dspring.profiles.active`/프로파일별 yml).
5. Nginx 443→localhost:8080 프록시 + 인증서, 도메인 A레코드 → EIP.
6. `POST https://<도메인>/skill/play` 헬스 점검 → 카카오 오픈빌더 스킬 URL 연결.

> 이후 코드 변경 배포는 5-2의 GitHub Actions로 자동화한다(수동 빌드/업로드 반복 제거).

### 5-2. GitHub Actions 자동 배포 (CI/CD)

단일 EC2 구성이라 **SSH 기반 배포**가 가장 단순하고 적합하다(ALB·ASG·CodeDeploy 불필요).
방식은 기존 GCP 배포에서 쓰던 **`stop.sh` → `start.sh` 스크립트 흐름**을 그대로 가져오되, 프로세스 관리는 `nohup` 대신 **systemd 에 위임**한다.
→ 배포 절차는 익숙한 스크립트 흐름을 유지하면서, **크래시 자동재시작·서버 재부팅 시 자동기동**은 systemd 가 보장한다(`nohup` 단독 방식의 약점 보완).

```
push(main) → [GitHub Actions Runner: ubuntu-latest]
  1. checkout
  2. JDK 21 셋업(+ Gradle 캐시)
  3. ./gradlew clean test bootJar -Pprofile=prod   # 테스트 통과해야 진행(품질 게이트)
  4. scp  deploy/scripts/*.sh   → EC2 /home/ubuntu/scripts   (배포 스크립트)
  5. scp  build/libs/baseball.jar → EC2 /home/ubuntu/deploy  (스테이징 JAR)
  6. ssh: stop.sh → (sleep 5) → start.sh
            └ start.sh: 백업 → JAR 교체 → systemctl start → 헬스체크
                                                  └ 실패 시 백업 JAR 으로 롤백 + 잡 실패
```

**서버 디렉터리 레이아웃** (EC2 `/home/ubuntu`)

| 경로 | 용도 |
|------|------|
| `scripts/stop.sh`, `scripts/start.sh` | 배포 스크립트(매 배포 시 scp 로 갱신) |
| `deploy/baseball.jar` | scp 로 올라온 **스테이징** JAR |
| `baseball.jar` | systemd 가 실행하는 **운영** JAR |
| `baseball.jar.bak` | 직전 운영본(롤백용) |
| `baseball.env` | DB 비밀 등 환경변수(권한 600, **CI 에 노출 안 함**) |
| `logs/deploy.log` | 배포 단계 로그(stop/start/롤백 기록) |

**핵심 설계 포인트**

- **익숙한 stop/start 스크립트 흐름 유지 + systemd 위임.** `stop.sh`는 `systemctl stop`, `start.sh`는 JAR 교체 후 `systemctl start`만 호출한다. 배포 절차는 GCP 때와 동일하게 읽히지만, 프로세스 생존 관리(크래시 자동재시작=`Restart=on-failure`, 부팅 자동기동=`enable`)는 systemd 가 책임진다.
- **JAR은 JVM 바이트코드 → 러너 아키텍처 무관.** 표준 `ubuntu-latest`(x86)에서 빌드해도 EC2 ARM에서 그대로 실행된다(ARM 크로스컴파일 불필요). 네이티브 이미지가 아니라서 가능.
- **테스트를 CI에서 실행(품질 게이트).** 테스트는 H2 인메모리라 외부 DB 없이 러너에서 단독 통과 → 깨진 코드는 배포되지 않는다.
- **비밀 분리(서버 측 보관).** DB 접속정보 등은 EC2 의 `baseball.env`(`EnvironmentFile`)에만 둔다. CI 에는 SSH 접속용 비밀만 보관 → **GitHub Actions 로그/러너에 DB 비밀이 절대 닿지 않는다.** (GCP 때처럼 CI 에서 `application.yml`을 생성·주입하지 않는다.)
- **다운타임/롤백.** 단일 인스턴스라 `stop → start` = 수 초 다운타임. 배포 후 **헬스체크로 검증**하고, 실패하면 **이전 jar로 롤백**한다(장애 조기 차단·재발 방지). 무중단이 필요해지면 ALB+ASG 블루/그린으로 확장(추후).
- **헬스체크 엔드포인트.** `spring-boot-starter-actuator`를 추가하고 `management.endpoints.web.exposure.include=health`로 `/actuator/health`를 노출하면 깔끔하다(추후 Micrometer 메트릭 노출에도 재사용). 액추에이터를 안 쓰면 `/skill/play`에 도움말 발화 POST로 200을 확인한다.

**GitHub Secrets** (Settings → Secrets and variables → Actions)

| 이름 | 용도 |
|------|------|
| `EC2_HOST` | EIP 또는 도메인 |
| `EC2_USER` | 접속 계정(예: `ubuntu`) |
| `EC2_SSH_KEY` | **배포 전용** SSH 개인키(PEM). 개인 키 재사용 금지 |

> GCP 워크플로의 `MIRI_ADMIN_DEMO_APPLICATION` 처럼 **설정 파일을 통째로 넣는 Secret 은 두지 않는다.** DB 비밀은 서버의 `baseball.env` 에만 존재한다.

**배포 스크립트** — `deploy/scripts/stop.sh`, `deploy/scripts/start.sh`

```bash
# stop.sh — baseball 서비스 정지 (systemd 위임)
set -euo pipefail
PROJECT_ROOT="/home/ubuntu"
DEPLOY_LOG="$PROJECT_ROOT/logs/deploy.log"
SERVICE="baseball"
mkdir -p "$PROJECT_ROOT/logs"
TIME_NOW=$(date +%c)

if sudo systemctl is-active --quiet "$SERVICE"; then
  echo "$TIME_NOW > 실행중인 $SERVICE 서비스 정지" >> "$DEPLOY_LOG"
  sudo systemctl stop "$SERVICE"
else
  echo "$TIME_NOW > 현재 실행중인 $SERVICE 서비스가 없습니다" >> "$DEPLOY_LOG"
fi
```

```bash
# start.sh — 새 JAR 교체 후 기동 + 헬스체크(실패 시 롤백)
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

# 1) 스테이징 JAR 확인 → 2) 현재본 백업 → 3) 교체
[ -f "$STAGED_JAR" ] || { echo "$TIME_NOW > [에러] 스테이징 JAR 없음" >> "$DEPLOY_LOG"; exit 1; }
[ -f "$LIVE_JAR" ] && cp -f "$LIVE_JAR" "$BACKUP_JAR"
mv -f "$STAGED_JAR" "$LIVE_JAR"
echo "$TIME_NOW > 새 JAR 배치 완료" >> "$DEPLOY_LOG"

# 4) systemd 기동
sudo systemctl start "$SERVICE"

# 5) 헬스체크(3초 x 10회 ≈ 30초)
ok=false
for i in $(seq 1 10); do
  curl -fsS "$HEALTH_URL" | grep -q '"status":"UP"' && { ok=true; break; }
  sleep 3
done

# 6) 실패 시 롤백
if [ "$ok" != "true" ]; then
  echo "$TIME_NOW > [실패] 헬스체크 실패 → 롤백" >> "$DEPLOY_LOG"
  [ -f "$BACKUP_JAR" ] && { mv -f "$BACKUP_JAR" "$LIVE_JAR"; sudo systemctl restart "$SERVICE"; }
  exit 1
fi
echo "$TIME_NOW > 배포 성공 (서비스 UP)" >> "$DEPLOY_LOG"
```

**워크플로** — `.github/workflows/deploy.yml`

```yaml
name: Deploy

on:
  push:
    branches: [ main ]
  workflow_dispatch:        # 콘솔에서 수동 실행도 허용

concurrency:                # 배포 직렬화(앞 배포 진행 중이면 취소)
  group: deploy-prod
  cancel-in-progress: true

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle      # 빌드 속도 개선

      - name: Test & Build (prod)
        run: |
          chmod +x gradlew
          ./gradlew clean test bootJar -Pprofile=prod   # 테스트 실패 시 배포 중단

      # 1) 배포 스크립트 업로드 (GCP 워크플로와 동일한 흐름)
      - name: Upload deploy scripts
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          source: deploy/scripts/*.sh
          target: /home/ubuntu/scripts
          strip_components: 2            # deploy/scripts 제거

      # 2) 새 JAR 업로드 (스테이징)
      - name: Upload JAR to EC2 (staging)
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          source: build/libs/baseball.jar
          target: /home/ubuntu/deploy/
          strip_components: 2            # build/libs 제거 → baseball.jar 만

      # 3) stop -> start (헬스체크/롤백은 start.sh 내부)
      - name: Run deploy scripts (stop -> start)
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            set -e
            chmod +x /home/ubuntu/scripts/*.sh
            /home/ubuntu/scripts/stop.sh
            sleep 5
            /home/ubuntu/scripts/start.sh
```

**sudoers 설정 (필수)** — 스크립트가 `systemctl`을 비밀번호 없이 호출하려면 해당 명령만 `NOPASSWD`로 허용한다(최소 권한).
`is-active`는 읽기 전용이라 root 불필요 → **start / stop / restart 3개만** 등록한다.

> ⚠️ 규칙 텍스트를 **셸에 직접 붙여넣지 말 것**(`(root)`를 셸 문법으로 오해해 에러). 아래 heredoc 방식으로 파일을 생성하고 `visudo -c`로 검증한다.

```bash
# EC2 에서 그대로 실행 (셸에 붙여넣어도 안전)
SC=$(which systemctl)              # 실제 경로 자동 확인 (보통 /usr/bin/systemctl)
sudo tee /etc/sudoers.d/baseball-deploy > /dev/null <<EOF
ubuntu ALL=(root) NOPASSWD: $SC start baseball, $SC stop baseball, $SC restart baseball
EOF
sudo chmod 440 /etc/sudoers.d/baseball-deploy
sudo visudo -c                     # "parsed OK" 떠야 정상

# 검증: 비밀번호 안 물으면 성공
sudo -n systemctl restart baseball && echo "NOPASSWD OK"
```

> 전제: systemd 유닛이 먼저 설치돼 있어야 한다(`sudo cp deploy/baseball.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable baseball`). `Unit baseball.service not loaded` 가 보이면 이 단계를 안 한 것.

**더 안전한 대안 (추후) — SSH 포트 없이 배포**

GitHub Actions 러너는 IP가 유동적이라 보안그룹 22번을 좁히기 어렵다. 보안을 더 높이려면 **AWS SSM Session Manager**로 전환한다: `aws-actions/configure-aws-credentials`(OIDC, 장기 키 불필요) + `aws ssm send-command`로 EC2에 배포 명령을 보내면 **22번 포트를 아예 닫을 수 있다.** 초기엔 SSH로 시작하고, 운영 안정화 단계에서 SSM/OIDC로 옮기는 것을 권장.

---

## 6. 모니터링 (CloudWatch)

### 6-1. 수집 대상

| 레이어 | 지표 | 수집 방법 |
|--------|------|-----------|
| **EC2(기본)** | CPUUtilization, NetworkIn/Out, **CPUCreditBalance**(버스터블 크레딧) | 기본 제공 |
| **EC2(추가)** | **mem_used_percent, disk_used_percent** | ⚠️ 기본 미수집 → **CloudWatch Agent 설치 필수** |
| **앱** | 요청 수·지연(elapsedMs)·slow·에러 | 9-F `LogTrace` 로그 → CloudWatch Logs → **Metric Filter** |
| **RDS** | CPUUtilization, DatabaseConnections, FreeableMemory, FreeStorageSpace, Read/WriteLatency | 기본 제공(Enhanced Monitoring 선택) |

> 메모리는 EC2 기본 지표에 **없다.** GC/메모리 압박을 보려면 CloudWatch Agent로 `mem_used_percent`를 올리거나, Micrometer로 JVM 힙 지표를 내보내야 한다.

### 6-2. 앱 로그 → 지표화 (9-F LogTrace 활용)

`LogTraceAspect`가 남기는 `phase=END ... status=... elapsedMs=... slow=...` 로그를 CloudWatch Logs로 보내고, **Metric Filter**로 다음을 카운트한다.

- `status=ERROR*` 발생 수 → **에러율 알람**
- `slow=true` 발생 수 → **지연 경보**(5초 근접 조기 감지)
- (선택) elapsedMs를 메트릭으로 추출해 p95/p99 추적

> 더 정밀한 p99가 필요하면 추후 **Micrometer Timer**로 전환해 `/actuator` 메트릭을 CloudWatch로 내보낸다(앱 코드 한 번 계측 → 벤더 독립).

### 6-3. 알람 (CloudWatch Alarm → SNS)

| 알람 | 지표 / 조건(시작 임계치, 튜닝 전제) | 의미 / 액션 |
|------|--------------------------------------|-------------|
| EC2 CPU 高 | `CPUUtilization > 70%` 10분 지속 | 부하 증가 추세 점검 |
| **EC2 크레딧 고갈** | `CPUCreditBalance` 지속 하락/0 근접 | 버스터블 한계 → m 계열 전환 검토 |
| EC2 메모리 高 | `mem_used_percent > 85%` 10분 | GC 압박 → EC2 한 단계 ↑ |
| 디스크 부족 | `disk_used_percent > 80%` | 볼륨 확장 |
| **앱 에러율** | Metric Filter `status=ERROR*` > N/분 | 장애 감지 → 로그 traceId로 추적 |
| **앱 지연** | `slow=true` > N/분 (또는 p99 상승) | 5초 근접 → 원인 구분(아래 7장) |
| RDS CPU 高 | `CPUUtilization > 80%` 10분 | DB 스케일업 검토 |
| RDS 커넥션 高 | `DatabaseConnections`가 max 근접 | 커넥션 풀/인스턴스 점검 |
| RDS 메모리 低 | `FreeableMemory` 지속 하락 | DB 한 단계 ↑ |
| RDS 스토리지 低 | `FreeStorageSpace < 임계` | 스토리지 확장(오토스케일링) |

> 임계치는 **시작값**이다. 배포 후 평시 baseline을 보고 조정한다(과민 알람 = 알람 피로). SNS는 이메일/슬랙 등으로 라우팅.

---

## 7. 스케일업 가이드 (임계 신호 → 대응)

> **원칙: 작게 시작 → 지표 보고 올린다.** 한 번에 한 축만 올려 효과를 측정한다(임팩트 측정).

### 7-1. 신호별 판단

```
임계 신호 보이면 스케일업:
  - 메모리 부족 / GC 잦음        → EC2 한 단계 ↑   (t4g.small → t4g.medium)
  - DB CPU / 커넥션 포화          → RDS 한 단계 ↑   (db.t4g.micro → db.t4g.small)
  - 5초 근접 지연 증가            → 원인(앱 vs DB) 구분 후 해당 쪽 ↑
```

| 신호 | 보는 지표 | 1차 대응 |
|------|-----------|----------|
| 메모리 부족 / GC 잦음 | `mem_used_percent`↑, (Micrometer) Full GC 빈도/시간↑ | **EC2 ↑** (small→medium, 4GB) |
| DB CPU/커넥션 포화 | RDS `CPUUtilization`↑, `DatabaseConnections`가 max 근접, `FreeableMemory`↓ | **RDS ↑** (micro→small) |
| 5초 근접 지연 | 앱 `slow=true`↑ / p99 elapsedMs↑ | **원인 구분 후** 해당 쪽 ↑ (아래) |

### 7-2. "5초 근접 지연"의 원인 구분 (앱 vs DB)

지연이 늘 때 **무턱대고 EC2를 키우지 않는다.** 동반 지표로 원인을 가른다.

- RDS `ReadLatency`/`WriteLatency`·`CPUUtilization`이 **함께 오르면 → DB 병목** → RDS 스케일업(또는 쿼리/인덱스 점검).
- RDS는 한산한데 앱 `elapsedMs`만 오르면 → **앱/JVM 병목**(GC·CPU 크레딧) → EC2 스케일업.
- 둘 다 정상인데 느리면 → 네트워크/외부 의존(카카오 응답 조립 외 호출) 점검.

### 7-3. 스케일업 실행 시 주의 (앞서 정리한 함정)

| 대상 | 절차 | 주의 |
|------|------|------|
| **EC2** | 중지 → 인스턴스 타입 변경 → 시작 | 다운타임 1~3분. **t4g(ARM) 계열 안에서만**(t3 x86 불가). **EIP 없으면 IP 변경됨** |
| **RDS** | 인스턴스 클래스 Modify → 재부팅 | Single-AZ는 다운타임 수 분. **무중단 가깝게** 하려면 먼저 Multi-AZ 전환 후 변경(failover ~1분) |
| **스토리지** | 온라인 확장 가능 | ⚠️ **축소 불가** — 한 번 늘리면 못 줄임 |
| **버스터블 한계** | 지속 고부하면 사양↑가 아니라 **m 계열로 패밀리 전환** | `CPUCreditBalance`로 판단 |
| **확장의 끝** | 수직 확장은 한계가 있음 | 무중단·고가용 필요 시 **ALB + 오토스케일링(수평)**, RDS Read Replica 검토 |

---

## 8. 백업 / 가용성

- RDS **자동 백업**(보존 7일) + 주요 변경 전 **수동 스냅샷**. 운영 안정 단계에서 **Multi-AZ** 전환 검토(페일오버 ~1분).
- 장애 시 **traceId**(9-F)로 요청 단위 로그를 추적 → 원인 분석 후 재발 방지(알람 임계/인덱스/사양 조정)로 마무리.

---

## 9. 비용 요약 (서울·근사)

| 구성 | 월(On-Demand) | 1년 Savings/RI 적용 시 |
|------|---------------|------------------------|
| 초기(t4g.small + db.t4g.micro + 스토리지) | ~$35 | ~$22~25 |
| 확장(t4g.medium + db.t4g.small) | ~$65 | ~$45 |
| 운영 HA(+ RDS Multi-AZ) | ~$95~ | — |

> 무료 OCI 대비 실제 비용이 증가한다(학습/포트폴리오 목적이면 OCI 유지도 합리적). 비용은 [AWS Pricing Calculator](https://calculator.aws/)로 확정.

---

## 부록 — 핵심 CloudWatch 지표 빠른 참조

| 네임스페이스 | 지표 | 본다 |
|--------------|------|------|
| AWS/EC2 | CPUUtilization, CPUCreditBalance, NetworkIn/Out | CPU·버스터블 크레딧 |
| CWAgent | mem_used_percent, disk_used_percent | 메모리·디스크(에이전트 필요) |
| AWS/RDS | CPUUtilization, DatabaseConnections, FreeableMemory, FreeStorageSpace, ReadLatency, WriteLatency | DB 자원·지연 |
| (LogsMetricFilter) | error_count, slow_count | 앱 에러율·지연(9-F LogTrace) |
