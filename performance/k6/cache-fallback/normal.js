import { printMockCounts, requestExternalInfo, resetMockRequests, setMockState } from './common.js';

export const options = {
  scenarios: {
    normal_lookup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    external_info_success: ['rate>0.99'],
    external_info_duration_ms: ['p(95)<1000', 'p(99)<2000'],
  },
};

export function setup() {
  setMockState('both', 'Started');
  resetMockRequests();
}

export default function () {
  requestExternalInfo((__VU + __ITER) % Number(__ENV.ORDER_COUNT || 100), {
    expectSuccess: true,
    expectedSource: 'LIVE',
  });
}

export function teardown() {
  printMockCounts('normal');
}
