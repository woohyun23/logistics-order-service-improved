import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

export const externalInfoSuccess = new Rate('external_info_success');
export const externalInfoError = new Rate('external_info_error');
export const expectedFailure = new Rate('external_info_expected_failure');
export const externalInfoRequests = new Counter('external_info_requests');
export const externalInfoDuration = new Trend('external_info_duration_ms', true);
export const liveResponses = new Counter('external_source_live');
export const cacheResponses = new Counter('external_source_cache');
export const cacheAge = new Trend('external_cache_age_ms', true);
export const productExternalCalls = new Gauge('product_external_calls');
export const deliveryExternalCalls = new Gauge('delivery_external_calls');

export const baseUrl = __ENV.BASE_URL || 'http://order-service:8080';
export const mockUrl = __ENV.MOCK_BASE_URL || 'http://external-services:8080';
export const orderCount = Number(__ENV.ORDER_COUNT || 100);

function sequenceUuid(prefix, index) {
  const sequence = String((index % orderCount) + 1).padStart(12, '0');
  return `${prefix}0000000-0000-0000-0000-${sequence}`;
}

export function orderId(index) {
  return sequenceUuid('1', index);
}

export function productId(index) {
  return sequenceUuid('2', index);
}

export function deliveryId(index) {
  return sequenceUuid('3', index);
}

export function requestExternalInfo(index, options = {}) {
  const response = http.get(
    `${baseUrl}/api/v1/orders/${orderId(index)}/external-info`,
    { tags: { name: 'GET /api/v1/orders/{orderId}/external-info' } },
  );
  const success = response.status === 200;
  externalInfoRequests.add(1);
  externalInfoDuration.add(response.timings.duration);
  externalInfoSuccess.add(success);
  externalInfoError.add(!success);

  let payload = null;
  if (success) {
    try {
      payload = response.json('data');
      recordSource(payload && payload.product);
      recordSource(payload && payload.deliveryStatus);
    } catch (error) {
      externalInfoSuccess.add(false);
      externalInfoError.add(true);
    }
  }

  if (options.expectSuccess === true) {
    check(response, { 'external info returns 200': (res) => res.status === 200 });
  }
  if (options.expectFailure === true) {
    const failedAsExpected = response.status >= 400;
    expectedFailure.add(failedAsExpected);
    check(response, { 'external info fails without usable cache': () => failedAsExpected });
  }
  if (options.expectedSource && payload) {
    check(payload, {
      [`product source is ${options.expectedSource}`]: (data) =>
        data.product && data.product.source === options.expectedSource,
      [`delivery source is ${options.expectedSource}`]: (data) =>
        data.deliveryStatus && data.deliveryStatus.source === options.expectedSource,
    });
  }

  sleep(Number(__ENV.SLEEP_SECONDS || 0.05));
  return { response, payload };
}

function recordSource(lookup) {
  if (!lookup) {
    return;
  }
  if (lookup.source === 'LIVE') {
    liveResponses.add(1);
  }
  if (lookup.source === 'CACHE') {
    cacheResponses.add(1);
    const fetchedAt = Date.parse(lookup.fetchedAt);
    if (!Number.isNaN(fetchedAt)) {
      cacheAge.add(Date.now() - fetchedAt);
    }
  }
}

export function setMockState(target, state) {
  const targets = target === 'both' ? ['product', 'delivery'] : [target];
  for (const service of targets) {
    const response = http.put(
      `${mockUrl}/__admin/scenarios/${service}-service/state`,
      JSON.stringify({ state }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    check(response, {
      [`${service} mock state changed to ${state}`]: (res) => res.status === 200,
    });
  }
}

export function resetMockRequests() {
  const response = http.del(`${mockUrl}/__admin/requests`);
  check(response, { 'WireMock request journal reset': (res) => res.status === 200 });
}

export function mockRequestCounts() {
  return {
    product: countRequests('/internal/api/v1/products/.*'),
    delivery: countRequests('/internal/api/v1/deliveries/.*/status'),
  };
}

function countRequests(urlPathPattern) {
  const response = http.post(
    `${mockUrl}/__admin/requests/count`,
    JSON.stringify({ method: 'GET', urlPathPattern }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200) {
    return -1;
  }
  return Number(response.json('count'));
}

export function printMockCounts(label) {
  const counts = mockRequestCounts();
  productExternalCalls.add(counts.product);
  deliveryExternalCalls.add(counts.delivery);
  console.log(`${label} externalCalls=${JSON.stringify(counts)}`);
}

export function warmAllOrders() {
  for (let index = 0; index < orderCount; index += 1) {
    const result = requestExternalInfo(index, { expectSuccess: true });
    if (result.response.status !== 200) {
      throw new Error(`cache warm-up failed for order index ${index}`);
    }
  }
}
