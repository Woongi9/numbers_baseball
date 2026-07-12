# 숫자야구 챗봇 — 부하 테스트 가이드 (처음 하는 사람용)

> 이 문서는 부하 테스트가 처음인 사람을 전제로 쓴다.
> "부하 테스트가 뭔지 / 왜 하는지 → 무엇을 순서대로 할지 → 체크리스트" 순서로 읽으면 된다.
> 인프라·모니터링 배경은 [`infra.md`](../infra.md), [`docs/2026-07-11-cloudwatch-monitoring-design.md`](cloudwatch-monitoring-check_list)를 참고.

---

## 0. 3줄 요약

- **도구는 [k6](https://k6.io) 하나만 쓴다.** 바이너리 1개 + JS 스크립트 1개. GUI 없음.
- **prod 아니라 `dev.numbers-baseball.com`(포트 9090)에 쏜다.** 같은 EC2·같은 RDS라서 사양은 동일, 실서비스만 안 건드림.
- **합격 기준은 처리량이 아니라 `p99 < 5초`다.** 카카오가 5초 안에 응답 못 받으면 실패로 취급하기 때문.

---

## 1. 부하 테스트란?

**정해진 양의 가짜 트래픽을 서버에 일부러 쏴서, 실제 사용자가 몰렸을 때 어떻게 되는지 미리 보는 것.**

- 요청을 1초에 몇 개씩, 몇 명이 동시에 보내는지를 스크립트로 흉내낸다.
- 그동안 서버의 **응답 시간**과 **에러율**, 그리고 EC2/RDS의 **CPU·메모리**를 같이 관찰한다.
- "몇 명까지 버티나 / 어디서 느려지나 / 어디서 터지나"를 **장애 나기 전에** 알아내는 게 목적.

비유: 다리 개통 전에 트럭을 줄줄이 올려 무게를 견디는지 보는 것. 사람이 다니기 전에.

### 부하 테스트의 종류 (이 프로젝트에서 쓸 것)

| 종류 | 무엇을 보나 | 이 프로젝트에서 |
|------|-------------|-----------------|
| **Smoke** | 스크립트·환경이 멀쩡한지 (부하 아님) | VU 1명, 1분. 매번 제일 먼저 |
| **Load(부하)** | 예상 피크 트래픽을 버티나 | 피크 가정 **~15 req/s**를 재현 |
| **Stress(한계)** | 어디서 무너지나 (한계점 찾기) | 부하를 계속 올려 p99가 5초를 넘거나 에러가 나는 지점 |
| **Soak(지속)** | 오래 돌리면 새는 게 없나 | **길게(1~2시간)** 돌려 메모리 누수·**버스터블 크레딧 고갈** 관찰 |

> **Soak를 빼먹지 마라.** 이 서버는 버스터블(t4g.small / db.t4g.micro)이다.
> 2분짜리 테스트는 **CPU 크레딧이 남아있어서 통과**하지만, 실제 피크가 1시간 이어지면
> 크레딧이 바닥나 갑자기 느려진다. 이 함정은 **지속 테스트라야만** 드러난다.

---

## 2. 왜 하는가 (이 챗봇 기준)

부하 자체는 작다(평균 0.5~1 req/s). 그런데도 하는 이유는 3가지다.

1. **카카오 5초 타임아웃.** 응답이 5초를 넘으면 사용자에겐 "응답 없음"이다.
   처리량(초당 몇 건)보다 **꼬리 지연(p99)이 5초 안에 안정적으로 들어오는지**가 핵심 지표다.
2. **버스터블 사양의 크레딧·GC 함정.** CPU 크레딧이 고갈되거나 JVM Full GC가 길어지면
   평소엔 빠르던 응답이 특정 순간 5초를 넘긴다. **언제 그렇게 되는지 미리** 알아야 한다.
3. **스케일업 판단 근거 확보.** [`infra.md`](../infra.md)의 스케일업 가이드는 "임계 신호 보이면 올린다"인데,
   그 **임계 신호가 몇 req/s에서 나오는지**를 부하 테스트로 수치화해 둔다. 감이 아니라 숫자로.

---

## 3. 준비물 (사전 조건)

- [ ] **k6 설치** — macOS: `brew install k6` / 확인: `k6 version`
- [ ] **dev 서버가 떠 있는지 확인** — `curl https://dev.numbers-baseball.com/actuator/health` → `{"status":"UP"}`
- [ ] **CloudWatch 대시보드/알람이 살아있는지 확인** (부하 중 EC2·RDS 지표를 봐야 함)
- [ ] **테스트 시간대 합의** — dev도 같은 RDS를 쓰므로, 부하 주는 동안 prod RDS에도 영향이 갈 수 있다. 트래픽 한산한 시간에.
- [ ] **dev DB 정리 계획** — 부하 테스트는 dev 스키마(`baseball_dev`)에 **진짜 게임/유저 row를 쌓는다.** 끝나고 지울 수 있게(또는 무시할 수 있게) 유저 ID에 접두어를 붙인다(아래 스크립트의 `loadtest-` 참고).

> ⚠️ **prod(`numbers-baseball.com`)에 직접 쏘지 않는다.** 실사용자 랭킹/게임이 오염되고, 5초 타임아웃 위반이 실제 장애가 된다. 반드시 dev.

---

## 4. 대상 이해 — 무엇을 쏘나

엔드포인트는 **하나**다: `POST /skill/play`. 카카오가 보내는 JSON 바디를 흉내내면 된다.

실제로 필요한 필드만 추리면 (코드: `SkillRequest.kt`):

```json
{
  "userRequest": {
    "utterance": "1234",
    "user": { "id": "loadtest-0001" },
    "chat": { "properties": { "botGroupKey": "loadtest-room" } }
  }
}
```

- `utterance` — 사용자 발화. `숫자야구`(게임 시작), `1234`(추측 제출), `랭킹`, `포기` 등.
- `user.id` — 유저 식별자. **게임 세션은 이 값으로 1인 1게임 관리된다**(`GameService`). → 동시성을 제대로 보려면 **VU마다 다른 id**를 써야 한다(같은 id면 같은 DB row를 두고 경합).
- `chat.properties.botGroupKey` — 채팅방(랭킹 그룹) 식별자. 없으면 1:1 채팅으로 처리.

### 현실적인 시나리오 = "한 판 흐름"

무작정 `1234`만 반복하면 "게임 없음" 경로만 탄다. 실제 사용자 흐름을 흉내내야 DB 쓰기·조회가 골고루 실행된다:

```
숫자야구  (게임 시작 → DB INSERT, 유저 등록)
  → 1234  (추측 제출 → 판정 + DB 조회/갱신)
  → 5678  (추측 제출)
  → 랭킹   (TOP10 조회 → 인덱스 쿼리)
```

---

## 5. 순서대로 진행 (단계별)

각 단계는 **앞 단계가 통과해야** 다음으로 간다. 한 번에 세게 쏘지 않는다.

### STEP 1 — Smoke (스크립트가 맞는지)
- VU 1명으로 1분. **부하가 아니라 스크립트 점검**이다.
- 목표: 에러 0%, 응답이 정상 카카오 포맷(`simpleText`/`BasicCard`)으로 오는지 눈으로 확인.
- 여기서 4xx/5xx가 나오면 **부하 문제가 아니라 스크립트/바디 문제**다. 고치고 다시.

### STEP 2 — Load (예상 피크 재현)
- 목표 부하: **~15 req/s** (infra.md 피크 가정의 상한).
- 3~5분 유지. **합격 기준: `http_req_duration` p95 < 2s, p99 < 5s, 에러율 < 1%.**
- 통과하면 "예상 피크는 문제없다"가 수치로 증명된 것.

### STEP 3 — Stress (한계점 찾기)
- 부하를 계단식으로 올린다: 15 → 30 → 50 → 75 → 100 req/s ...
- **관찰: p99가 5초를 처음 넘는 지점 / 에러가 처음 나는 지점.** 그게 이 사양의 한계다.
- 동시에 CloudWatch에서 **무엇이 먼저 포화되는지** 본다(EC2 CPU? RDS CPU? 커넥션? 메모리?).
  → 그게 스케일업의 첫 번째 대상이다.

### STEP 4 — Soak (지속, 함정 탐지)
- STEP 2의 안전한 부하(~10~15 req/s)를 **1~2시간** 유지.
- 관찰: 시간이 갈수록 p99가 **서서히 올라가는지**(메모리 누수 / GC 악화 / **크레딧 고갈**).
- EC2 `CPUCreditBalance`가 계속 내려가 0에 가까워지면 → 버스터블 한계. m 계열 전환 신호.

### STEP 5 — 정리 & 기록
- dev DB에 쌓인 `loadtest-*` 데이터 정리(또는 무시).
- **결과를 남긴다**: 각 단계의 p95/p99/에러율, 한계 req/s, 먼저 포화된 자원. 다음 스케일업 결정의 근거.

---

## 6. k6 스크립트 (그대로 시작용)

`load/skill-play.js` 로 저장. 환경변수로 부하량을 바꾼다.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'https://dev.numbers-baseball.com';

// 단계는 실행 시 --stage 로 덮어써도 되고, 아래 기본값을 쓴다.
export const options = {
  stages: [
    { duration: '30s', target: 5 },   // 워밍업
    { duration: '3m',  target: 15 },  // 예상 피크 유지 (STEP 2)
    { duration: '30s', target: 0 },   // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'], // 5초 타임아웃이 곧 합격선
    http_req_failed:   ['rate<0.01'],
  },
};

function play(utterance, userId, room) {
  const body = JSON.stringify({
    userRequest: {
      utterance,
      user: { id: userId },
      chat: { properties: { botGroupKey: room } },
    },
  });
  const res = http.post(`${BASE}/skill/play`, body, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, {
    'status 200': (r) => r.status === 200,      // 카카오 스킬은 에러도 200으로 응답
    'has body':   (r) => r.body && r.body.length > 0,
  });
  return res;
}

export default function () {
  // VU·반복마다 고유 유저 → 1인 1게임 경합 없이 실제 사용자처럼
  const userId = `loadtest-${__VU}-${__ITER}`;
  const room = 'loadtest-room';

  play('숫자야구', userId, room);   // 게임 시작
  sleep(1);
  play('1234', userId, room);       // 추측
  sleep(1);
  play('5678', userId, room);       // 추측
  sleep(1);
  play('랭킹', userId, room);       // 랭킹 조회
  sleep(1);
}
```

### 실행

```bash
# STEP 1 — Smoke (VU 1, 1분)
k6 run --vus 1 --duration 1m load/skill-play.js

# STEP 2 — Load (스크립트 기본 stages 사용)
k6 run load/skill-play.js

# STEP 3 — Stress (부하 계단식으로 올리기; stages를 덮어씀)
k6 run --stage 1m:30 --stage 1m:50 --stage 1m:75 --stage 1m:100 load/skill-play.js

# STEP 4 — Soak (지속)
k6 run --stage 1m:15 --stage 2h:15 --stage 1m:0 load/skill-play.js
```

> `sleep(1)`은 사람이 생각하는 시간(think time)이다. 이게 있어야 "동시 접속자 수(VU)"와 "초당 요청(req/s)"이 현실적으로 맞아떨어진다. VU 15명 × 4요청/약5초 ≈ 12 req/s 수준.

---

## 7. 결과 읽는 법 (k6 출력 + CloudWatch 같이)

**k6 요약(테스트 끝나면 콘솔에 출력)** — 이 3개만 먼저 본다:

| 항목 | 의미 | 합격선 |
|------|------|--------|
| `http_req_duration` **p(99)** | 느린 쪽 1%의 응답 시간 = 꼬리 지연 | **< 5000ms** (카카오 타임아웃) |
| `http_req_duration` **p(95)** | 평상시 체감 지연 | < 2000ms 권장 |
| `http_req_failed` | 실패율 | < 1% |

> 평균(avg)에 속지 마라. 평균 800ms라도 p99가 6초면 **사용자 1%는 매번 타임아웃**이다. 5초 서비스에서 중요한 건 avg가 아니라 **p99**.

**CloudWatch(부하 도는 동안 실시간으로)** — 무엇이 먼저 포화되나:

| 지표 | 포화 신호 | 뜻 |
|------|-----------|-----|
| EC2 `CPUUtilization` | 지속 70%+ | 앱/JVM이 CPU 병목 |
| EC2 `CPUCreditBalance` | 계속 하락 → 0 근접 | 버스터블 크레딧 고갈(Soak에서 주로) |
| EC2 `mem_used_percent` | 85%+ | GC 압박 → EC2 한 단계 ↑ 신호 |
| RDS `CPUUtilization` | 80%+ | DB 병목 |
| RDS `DatabaseConnections` | max 근접 | 커넥션 풀 한계 |
| 앱 로그 `slow=true` 카운트 | 급증 | 5초 근접 요청 발생(LogTrace) |

**진단 규칙**(infra.md 7장과 동일):
- p99↑ + **RDS CPU/Latency 동반 상승** → **DB 병목** → RDS 스케일업 또는 쿼리/인덱스 점검.
- p99↑ + RDS는 한산 → **앱/JVM 병목**(CPU 크레딧·GC) → EC2 스케일업.

---

## 8. 최종 체크리스트

**시작 전**
- [ ] k6 설치 확인 (`k6 version`)
- [ ] dev 서버 `UP` 확인 (`/actuator/health`)
- [ ] CloudWatch 대시보드 열어두기 (EC2·RDS·앱 로그)
- [ ] prod가 아니라 **dev**를 대상으로 하는지 `BASE_URL` 재확인
- [ ] 트래픽 한산한 시간대인지 확인 (dev·prod가 RDS 공유)
- [ ] 유저 ID에 `loadtest-` 접두어 (정리·구분용)

**진행 중 (단계 순서 지키기)**
- [ ] STEP 1 Smoke 통과 (에러 0, 응답 포맷 정상)
- [ ] STEP 2 Load 통과 (p99 < 5s, 에러 < 1%)
- [ ] STEP 3 Stress — 한계 req/s와 먼저 포화된 자원 기록
- [ ] STEP 4 Soak — 시간 경과에 따른 p99 추이 / 크레딧 잔량 기록
- [ ] 각 단계 중 CloudWatch에서 병목 자원 확인

**끝난 뒤**
- [ ] dev DB의 `loadtest-*` 데이터 정리(또는 무시 처리)
- [ ] 결과 기록: 단계별 p95/p99/에러율, 한계 req/s, 병목 자원
- [ ] 스케일업 필요 신호가 보였으면 infra.md 7장으로 연결

---

## 부록 — 자주 하는 오해

- **"처리량(RPS)이 높을수록 좋다"** → 아니다. 이 서비스의 목표는 높은 RPS가 아니라 **낮은 RPS에서도 p99가 5초를 안 넘는 것**.
- **"짧게 돌려서 통과하면 끝"** → 버스터블 크레딧·메모리 누수는 **지속(Soak)** 이라야 드러난다.
- **"prod에 쏴야 진짜 부하 테스트"** → dev가 같은 사양·같은 RDS다. prod는 실사용자 오염·실장애 위험만 크다.
- **"평균 응답이 빠르니 괜찮다"** → 5초 서비스에서 중요한 건 avg가 아니라 **꼬리(p99)**.
</content>
</invoke>
