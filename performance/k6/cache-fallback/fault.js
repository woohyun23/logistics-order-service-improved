import { printMockCounts, requestExternalInfo, resetMockRequests, setMockState } from './common.js';

const target = __ENV.FAULT_TARGET || 'both';
const faultMode = __ENV.FAULT_MODE || 'SERVER_ERROR';

export const options = {
  scenarios: {
    cached_fault_lookup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '20s',
    },
  },
};

export function setup() {
  setMockState('both', 'Started');
  setMockState(target, faultMode);
  resetMockRequests();
}

export default function () {
  requestExternalInfo((__VU + __ITER) % Number(__ENV.ORDER_COUNT || 100));
}

export function teardown() {
  printMockCounts(`fault target=${target} mode=${faultMode}`);
}
