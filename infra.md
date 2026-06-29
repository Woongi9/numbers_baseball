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

단일 EC2 + systemd 구성이라 **SSH 기반 배포**가 가장 단순하고 적합하다(ALB·ASG·CodeDeploy 불필요).

```
push(main) → [GitHub Actions Runner: ubuntu-latest]
  1. checkout
  2. JDK 21 셋업(+ Gradle 캐시)
  3. ./gradlew clean test bootJar   # 테스트 통과해야 다음 단계 진행(품질 게이트)
  4. scp baseball.jar → EC2 스테이징 디렉터리
  5. ssh: 기존 jar 백업 → 교체 → systemctl restart → 헬스체크
                                         └ 실패 시 백업으로 롤백 + 잡 실패
```

**핵심 설계 포인트**

- **JAR은 JVM 바이트코드 → 러너 아키텍처 무관.** 표준 `ubuntu-latest`(x86)에서 빌드해도 EC2 ARM에서 그대로 실행된다(ARM 크로스컴파일 불필요). 네이티브 이미지가 아니라서 가능.
- **테스트를 CI에서 실행(품질 게이트).** 테스트는 H2 인메모리라 외부 DB 없이 러너에서 단독 통과 → 깨진 코드는 배포되지 않는다.
- **비밀 분리.** DB 접속정보 등은 **서버 프로파일에만** 둔다. CI에는 SSH 접속용 비밀만 보관(코드/CI에 DB 비밀 노출 금지).
- **다운타임/롤백.** 단일 인스턴스라 재시작 = 수 초 다운타임. 배포 후 **헬스체크로 검증**하고, 실패하면 **이전 jar로 롤백**한다(장애 조기 차단·재발 방지). 무중단이 필요해지면 ALB+ASG 블루/그린으로 확장(추후).
- **헬스체크 엔드포인트.** `spring-boot-starter-actuator`를 추가하고 `management.endpoints.web.exposure.include=health`로 `/actuator/health`를 노출하면 깔끔하다(추후 Micrometer 메트릭 노출에도 재사용). 액추에이터를 안 쓰면 `/skill/play`에 도움말 발화 POST로 200을 확인한다.

**GitHub Secrets** (Settings → Secrets and variables → Actions)

| 이름 | 용도 |
|------|------|
| `EC2_HOST` | EIP 또는 도메인 |
| `EC2_USER` | 접속 계정(예: `ubuntu`) |
| `EC2_SSH_KEY` | **배포 전용** SSH 개인키(PEM). 개인 키 재사용 금지 |

**워크플로** — `.github/workflows/deploy.yml`

```yaml
name: Deploy

on:
  push:
    branches: [ main ]
  workflow_dispatch:        # 콘솔에서 수동 실행도 허용

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

      - name: Test & Build
        run: |
          chmod +x gradlew
          ./gradlew clean test bootJar   # 테스트 실패 시 여기서 배포 중단

      - name: Upload JAR to EC2 (staging)
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          source: build/libs/baseball.jar
          target: /home/ubuntu/deploy/      # 스테이징 경로
          strip_components: 2               # build/libs 제거 → baseball.jar 만 업로드

      - name: Swap, restart, health-check (with rollback)
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            set -e
            cd /home/ubuntu
            cp -f baseball.jar baseball.jar.bak 2>/dev/null || true   # 현재본 백업
            mv -f deploy/baseball.jar baseball.jar                    # 새 버전 교체
            sudo systemctl restart baseball
            sleep 5
            if ! curl -fsS http://localhost:8080/actuator/health; then
              echo "health check failed → rollback"
              mv -f baseball.jar.bak baseball.jar
              sudo systemctl restart baseball
              exit 1
            fi
            echo "deploy ok"
```

> `systemctl restart baseball`을 비밀번호 없이 쓰려면 배포 계정에 sudoers `NOPASSWD` 한정 권한(해당 명령만)을 부여한다.

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
