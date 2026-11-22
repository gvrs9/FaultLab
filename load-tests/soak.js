// Soak: moderate constant load for a long time. Look for leaks, pool creep, GC and latency drift.
//   RATE=50 DURATION=1h
import { placeOrder, defaultThresholds, SUMMARY_TREND_STATS } from './lib/common.js';

const RATE = parseInt(__ENV.RATE || '50', 10);

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1h',
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
    },
  },
  thresholds: defaultThresholds({ 'http_req_duration{name:POST /api/orders}': ['p(99)<2000'] }),
};

export default function () { placeOrder(); }

