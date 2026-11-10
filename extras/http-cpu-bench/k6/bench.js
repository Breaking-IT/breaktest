/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// k6 version of the benchmark scenarios; run.sh passes the settings as environment variables.
//
// keepalive: BENCH_THREADS VUs, each pacing one GET every BENCH_PACING_MS on its own
//            kept-alive connection (same model as the BreakTest closed-model plans).
// newconn:   arrivals at BENCH_RATE/s, a new connection for every request.
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';

const url = __ENV.BENCH_URL;
const scenario = __ENV.BENCH_SCENARIO;
const threads = parseInt(__ENV.BENCH_THREADS, 10);
const duration = `${__ENV.BENCH_DURATION}s`;
const pacingMs = parseInt(__ENV.BENCH_PACING_MS, 10);
const rate = parseInt(__ENV.BENCH_RATE, 10);

export const options = {
    insecureSkipTLSVerify: true,
    noConnectionReuse: scenario === 'newconn',
    discardResponseBodies: false,
    summaryTrendStats: ['avg', 'p(99)'],
    scenarios: {
        bench: scenario === 'newconn'
            ? {
                executor: 'constant-arrival-rate',
                rate: rate,
                timeUnit: '1s',
                duration: duration,
                preAllocatedVUs: threads,
                maxVUs: threads * 4,
            }
            : {
                executor: 'constant-vus',
                vus: threads,
                duration: duration,
            },
    },
};

export default function () {
    if (scenario !== 'newconn' && exec.vu.iterationInScenario === 0) {
        // One VU per millisecond, like the other tools' ramp-up.
        sleep((exec.vu.idInTest - 1) / 1000);
    }
    const started = Date.now();
    const response = http.get(url);
    if (response.status !== 200 || response.body.length === 0) {
        throw new Error(`unexpected response ${response.status}`);
    }
    if (scenario !== 'newconn') {
        // Start-to-start pacing, like BreakTest's fixed thread group pacing.
        const remaining = pacingMs - (Date.now() - started);
        if (remaining > 0) {
            sleep(remaining / 1000);
        }
    }
}
