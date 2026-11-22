// Spike: steady baseline, sudden jump, back to baseline. Watch the bulkhead, Hikari pending and recovery.
//   BASE_RATE=25 SPIKE_RATE=500
import { placeOrder, SUMMARY_TREND_STATS } from './lib/common.js';

const BASE = parseInt(__ENV.BASE_RATE || '25', 10);
const SPIKE = parseInt(__ENV.SPIKE_RATE || '500', 10);

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: Math.max(200, SPIKE * 3),
      stages: [
        { target: BASE, duration: '1m' },
        { target: SPIKE, duration: '10s' },
        { target: SPIKE, duration: '1m' },
        { target: BASE, duration: '10s' },
        { target: BASE, duration: '2m' },
      ],
    },
  },
  thresholds: { orders_error_rate: [{ threshold: 'rate<0.10', abortOnFail: false }] },
};

export default function () { placeOrder(); }

