import { requestExternalInfo, resetMockRequests, setMockState } from './common.js';

export const options = {
  scenarios: {
    cache_warmup: {
      executor: 'shared-iterations',
      // __ITER는 VU마다 0부터 시작하므로, 결정적 ID 1..N을 빠짐없이
      // 한 번씩 예열하기 위해 의도적으로 단일 VU를 사용한다.
      vus: 1,
      iterations: Number(__ENV.ORDER_COUNT || 100),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    external_info_success: ['rate==1'],
    checks: ['rate==1'],
  },
};

export function setup() {
  setMockState('both', 'Started');
  resetMockRequests();
}

export default function () {
  requestExternalInfo(__ITER, { expectSuccess: true, expectedSource: 'LIVE' });
}
