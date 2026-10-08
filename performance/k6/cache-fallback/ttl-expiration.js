import { sleep } from 'k6';
import {
  printMockCounts,
  requestExternalInfo,
  resetMockRequests,
  setMockState,
  warmAllOrders,
} from './common.js';

const target = __ENV.TTL_TARGET || 'delivery';
const defaultTtl = target === 'product' ? 300 : 30;
const ttlSeconds = Number(__ENV.TTL_SECONDS || defaultTtl);

export const options = {
  scenarios: {
    expired_cache_fault: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 5),
      iterations: Number(__ENV.ITERATIONS || 20),
      maxDuration: '2m',
    },
  },
  thresholds: {
    external_info_expected_failure: ['rate>0.99'],
  },
  setupTimeout: `${ttlSeconds + 120}s`,
};

export function setup() {
  setMockState('both', 'Started');
  warmAllOrders();
  sleep(ttlSeconds + 1);
  setMockState(target, __ENV.FAULT_MODE || 'SERVER_ERROR');
  resetMockRequests();
}

export default function () {
  requestExternalInfo(__ITER, { expectFailure: true });
}

export function teardown() {
  printMockCounts(`ttl-expiration target=${target} ttlSeconds=${ttlSeconds}`);
}
