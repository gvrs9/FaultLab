// Smoke: a few requests to verify the whole path works (also used by scripts/smoke-test.ps1).
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, placeOrder, SUMMARY_TREND_STATS } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: parseInt(__ENV.ITERATIONS || '20', 10),
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: { checks: ['rate==1.0'], orders_error_rate: ['rate==0'] },
};

export default function () {
  const res = placeOrder();
  if (res.status === 201) {
    const get = http.get(`${BASE_URL}/api/orders/${res.json('orderId')}`, { tags: { name: 'GET /api/orders/{id}' } });
    check(get, { 'GET order 200': (r) => r.status === 200, 'status CONFIRMED': (r) => r.json('status') === 'CONFIRMED' });
  }
}

