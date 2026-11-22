// Shared k6 helpers for FaultLab.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const RATE = parseInt(__ENV.RATE || '50', 10);            // 10 | 25 | 50 | 100 | 200 | 500
export const DURATION = __ENV.DURATION || '2m';
export const PRODUCT_COUNT = parseInt(__ENV.PRODUCT_COUNT || '1000', 10);

// Custom metrics
export const successfulRequests = new Counter('orders_successful');   // 200/201
export const failedRequests = new Counter('orders_failed');           // 5xx, network errors
export const degradedRequests = new Counter('orders_degraded');       // 202 RESERVATION_UNKNOWN
export const rejectedRequests = new Counter('orders_rejected');       // 409 business rejection
export const errorRate = new Rate('orders_error_rate');
export const downstreamAttempts = new Counter('downstream_attempts'); // from X-Downstream-Attempts
export const retryAmplification = new Trend('retry_amplification');   // attempts per order request

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

// Random product over a large, well-stocked catalog: inventory exhaustion never dominates results.
function randomProduct() {
  const n = Math.floor(Math.random() * PRODUCT_COUNT) + 1;
  return `P-${String(n).padStart(4, '0')}`;
}

function uuid() {
  // RFC4122-ish v4, sufficient as an idempotency key in tests
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function placeOrder() {
  const res = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ productId: randomProduct(), quantity: 1 }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() },
      tags: { name: 'POST /api/orders' },
      timeout: __ENV.REQUEST_TIMEOUT || '30s',
      responseCallback: http.expectedStatuses(200, 201, 202, 409),
    },
  );

  const ok = res.status === 200 || res.status === 201;
  const failed = res.status === 0 || res.status >= 500;
  if (ok) successfulRequests.add(1);
  if (failed) failedRequests.add(1);
  if (res.status === 202) degradedRequests.add(1);
  if (res.status === 409) rejectedRequests.add(1);
  errorRate.add(failed);

  const attempts = parseInt(res.headers['X-Downstream-Attempts'] || '0', 10);
  if (!Number.isNaN(attempts)) {
    downstreamAttempts.add(attempts);
    retryAmplification.add(attempts);
  }

  check(res, {
    'status is 2xx/409': (r) => [200, 201, 202, 409].includes(r.status),
    'has order id': (r) => r.status >= 500 || r.status === 0 || (r.json('orderId') || '') !== '',
  });
  return res;
}

export function defaultThresholds(extra = {}) {
  return Object.assign({
    orders_error_rate: ['rate<0.05'],
    'http_req_duration{name:POST /api/orders}': ['p(95)<1000'],
  }, extra);
}
