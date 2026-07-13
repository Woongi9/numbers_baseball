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
