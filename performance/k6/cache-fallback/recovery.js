import { check, sleep } from 'k6';
import {
  printMockCounts,
  requestExternalInfo,
  resetMockRequests,
  setMockState,
  warmAllOrders,
} from './common.js';

export const options = {
  scenarios: {
    recovered_lookup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '15s',
    },
  },
  thresholds: {
    external_info_success: ['rate>0.99'],
    checks: ['rate==1'],
  },
  setupTimeout: '3m',
};

export function setup() {
  setMockState('both', 'Started');
  warmAllOrders();
  setMockState('both', 'SERVER_ERROR');
  for (let index = 0; index < 10; index += 1) {
    requestExternalInfo(index);
  }
  setMockState('both', 'RECOVERED');
  sleep(Number(__ENV.CIRCUIT_OPEN_WAIT_SECONDS || 15));

  for (let index = 0; index < 3; index += 1) {
    const result = requestExternalInfo(index, { expectSuccess: true });
    check(result.payload, {
      'recovery probe uses LIVE product': (data) => data && data.product.source === 'LIVE',
      'recovery probe uses LIVE delivery': (data) => data && data.deliveryStatus.source === 'LIVE',
    });
  }
  resetMockRequests();
}

export default function () {
  const result = requestExternalInfo((__VU + __ITER) % Number(__ENV.ORDER_COUNT || 100), {
    expectSuccess: true,
    expectedSource: 'LIVE',
  });
  check(result.payload, {
    'latest product is returned after recovery': (data) =>
      data && data.product.data.name === '복구 후 최신 상품',
    'latest delivery status is returned after recovery': (data) =>
      data && data.deliveryStatus.data.status === 'DELIVERED',
  });
}

export function teardown() {
  printMockCounts('recovery');
}
