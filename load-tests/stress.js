// Stress: step through 10 -> 25 -> 50 -> 100 -> 200 -> 500 RPS and find the breaking point.
//   STEP=1m (hold time per step)  MAX_RATE=500
import { placeOrder, SUMMARY_TREND_STATS } from './lib/common.js';

const STEP = __ENV.STEP || '1m';
const MAX_RATE = parseInt(__ENV.MAX_RATE || '500', 10);
const TARGETS = [10, 25, 50, 100, 200, 500].filter((r) => r <= MAX_RATE);

const stages = [];
for (const t of TARGETS) {
  stages.push({ target: t, duration: '20s' });   // ramp to the step
  stages.push({ target: t, duration: STEP });    // hold
}
stages.push({ target: 0, duration: '20s' });

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    stress: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: Math.max(200, MAX_RATE * 3),
      stages,
    },
  },
  // Stress tests are expected to fail eventually: thresholds only flag, never abort.
  thresholds: { orders_error_rate: [{ threshold: 'rate<0.10', abortOnFail: false }] },
};

export default function () { placeOrder(); }

