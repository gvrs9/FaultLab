// Constant arrival rate with an optional ramp-up.
//   RATE=10|25|50|100|200|500  DURATION=5m  RAMP_UP=30s
import { DURATION, RATE, placeOrder, defaultThresholds, SUMMARY_TREND_STATS } from './lib/common.js';

const RAMP_UP = __ENV.RAMP_UP || '30s';

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    ramp_up: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(50, RATE * 4),
      stages: [{ target: RATE, duration: RAMP_UP }],
    },
    constant: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: RAMP_UP,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(50, RATE * 4),
    },
  },
  thresholds: defaultThresholds(),
};

export default function () { placeOrder(); }

