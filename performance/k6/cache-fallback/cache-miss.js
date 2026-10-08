import { printMockCounts, requestExternalInfo, resetMockRequests, setMockState } from './common.js';

export const options = {
  scenarios: {
    cache_miss_fault: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '10s',
    },
  },
  thresholds: {
    external_info_expected_failure: ['rate>0.99'],
  },
};

export function setup() {
  setMockState('both', __ENV.FAULT_MODE || 'SERVER_ERROR');
  resetMockRequests();
}

export default function () {
  requestExternalInfo((__VU + __ITER) % Number(__ENV.ORDER_COUNT || 100), {
    expectFailure: true,
  });
}

export function teardown() {
  printMockCounts('cache-miss');
}
