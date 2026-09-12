<!--
Copyright 2024-2026 Breaking IT

Licensed under the BreakTest Community Source License 1.0.
You may not use this file except in compliance with that license.
See the LICENSE file at the root of this distribution.
-->

# Current JDK 21 measurements

CPU is process ns/op (mean ± JMH 99.9% CI half-width); allocations are B/op. Positive CPU change means more CPU with deferral. See README for fixture sizes and limits.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h1 | 400 | unread | 178.5 ± 13.2 | 124.7 ± 10.1 | -30.2% | 1456 → 1184 |
| h1 | 400 | request | 177.8 ± 1.3 | 161.4 ± 6.4 | -9.2% | 1456 → 1472 |
| h1 | 400 | response | 171.9 ± 2.9 | 157.8 ± 15.1 | -8.2% | 1456 → 1664 |
| h1 | 400 | both25 | 186.8 ± 14.7 | 148.3 ± 16.2 | -20.6% | 1456 → 1376 |
| h1 | 400 | both50 | 191.1 ± 12.4 | 161.6 ± 2.1 | -15.4% | 1456 → 1568 |
| h1 | 400 | both75 | 175.2 ± 4.5 | 170.8 ± 13.5 | -2.5% | 1456 → 1760 |
| h1 | 400 | both100 | 180.6 ± 4.1 | 192.5 ± 3.7 | +6.6% | 1456 → 1952 |

**h1, 400 bytes:** sampled mean CPU sign crossings: 75–100% (linear estimate 82%). These are fixture-specific estimates, not universal thresholds; inspect the intervals.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h1 | 1,600 | unread | 561.0 ± 12.8 | 224.5 ± 2.5 | -60.0% | 5304 → 2528 |
| h1 | 1,600 | request | 574.0 ± 20.7 | 381.4 ± 5.9 | -33.6% | 5304 → 3408 |
| h1 | 1,600 | response | 571.9 ± 33.8 | 337.4 ± 22.9 | -41.0% | 5304 → 4208 |
| h1 | 1,600 | both25 | 571.6 ± 6.5 | 314.1 ± 50.0 | -45.0% | 5304 → 3168 |
| h1 | 1,600 | both50 | 571.4 ± 12.2 | 355.6 ± 19.2 | -37.8% | 5304 → 3836 |
| h1 | 1,600 | both75 | 571.1 ± 15.0 | 411.4 ± 10.6 | -28.0% | 5304 → 4448 |
| h1 | 1,600 | both100 | 564.9 ± 8.7 | 478.3 ± 15.7 | -15.3% | 5304 → 5088 |

**h1, 1,600 bytes:** sampled mean CPU sign crossings: none in the 0–100% both-read sweep. These are fixture-specific estimates, not universal thresholds; inspect the intervals.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h1 | 8,192 | unread | 1610.2 ± 51.3 | 335.4 ± 12.1 | -79.2% | 22144 → 5856 |
| h1 | 8,192 | request | 1629.3 ± 34.4 | 579.7 ± 22.5 | -64.4% | 22144 → 10032 |
| h1 | 8,192 | response | 1612.4 ± 40.3 | 611.3 ± 13.3 | -62.1% | 22144 → 14128 |
| h1 | 8,192 | both25 | 1623.7 ± 77.4 | 464.2 ± 6.2 | -71.4% | 22144 → 8968 |
| h1 | 8,192 | both50 | 1629.4 ± 37.5 | 616.5 ± 26.1 | -62.2% | 22144 → 12080 |
| h1 | 8,192 | both75 | 1626.2 ± 45.1 | 729.1 ± 17.4 | -55.2% | 22144 → 15192 |
| h1 | 8,192 | both100 | 1617.4 ± 22.4 | 864.2 ± 15.9 | -46.6% | 22144 → 18304 |

**h1, 8,192 bytes:** sampled mean CPU sign crossings: none in the 0–100% both-read sweep. These are fixture-specific estimates, not universal thresholds; inspect the intervals.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h2 | 400 | unread | 124.4 ± 3.2 | 75.4 ± 4.7 | -39.4% | 1264 → 720 |
| h2 | 400 | request | 121.3 ± 5.7 | 111.2 ± 2.9 | -8.4% | 1264 → 1008 |
| h2 | 400 | response | 122.1 ± 9.0 | 116.0 ± 2.0 | -5.0% | 1264 → 1200 |
| h2 | 400 | both25 | 124.6 ± 9.5 | 98.9 ± 3.5 | -20.6% | 1264 → 912 |
| h2 | 400 | both50 | 128.4 ± 3.6 | 120.9 ± 2.8 | -5.9% | 1264 → 1104 |
| h2 | 400 | both75 | 121.4 ± 2.3 | 140.5 ± 2.1 | +15.7% | 1264 → 1296 |
| h2 | 400 | both100 | 121.7 ± 4.5 | 156.1 ± 3.6 | +28.3% | 1264 → 1488 |

**h2, 400 bytes:** sampled mean CPU sign crossings: 50–75% (linear estimate 57%). These are fixture-specific estimates, not universal thresholds; inspect the intervals.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h2 | 1,600 | unread | 445.2 ± 38.4 | 150.0 ± 2.2 | -66.3% | 5112 → 1008 |
| h2 | 1,600 | request | 428.1 ± 20.0 | 306.1 ± 2.9 | -28.5% | 5112 → 1888 |
| h2 | 1,600 | response | 438.7 ± 31.0 | 319.9 ± 7.3 | -27.1% | 5112 → 2688 |
| h2 | 1,600 | both25 | 435.0 ± 30.9 | 237.0 ± 3.3 | -45.5% | 5112 → 1648 |
| h2 | 1,600 | both50 | 445.3 ± 48.4 | 323.0 ± 9.1 | -27.5% | 5112 → 2288 |
| h2 | 1,600 | both75 | 425.3 ± 7.4 | 392.7 ± 5.0 | -7.7% | 5112 → 2928 |
| h2 | 1,600 | both100 | 426.5 ± 13.5 | 479.6 ± 19.1 | +12.4% | 5112 → 3568 |

**h2, 1,600 bytes:** sampled mean CPU sign crossings: 75–100% (linear estimate 85%). These are fixture-specific estimates, not universal thresholds; inspect the intervals.

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h2 | 8,192 | unread | 818.0 ± 29.5 | 184.9 ± 66.5 | -77.4% | 21952 → 1008 |
| h2 | 8,192 | request | 804.9 ± 38.5 | 402.7 ± 19.8 | -50.0% | 21952 → 5184 |
| h2 | 8,192 | response | 832.3 ± 48.0 | 494.9 ± 24.5 | -40.5% | 21952 → 9280 |
| h2 | 8,192 | both25 | 802.5 ± 35.8 | 305.6 ± 5.1 | -61.9% | 21952 → 4120 |
| h2 | 8,192 | both50 | 801.2 ± 18.6 | 454.5 ± 5.5 | -43.3% | 21952 → 7232 |
| h2 | 8,192 | both75 | 842.3 ± 40.7 | 597.2 ± 30.5 | -29.1% | 21952 → 10344 |
| h2 | 8,192 | both100 | 812.3 ± 26.0 | 746.3 ± 16.6 | -8.1% | 21952 → 13456 |

**h2, 8,192 bytes:** sampled mean CPU sign crossings: none in the 0–100% both-read sweep. These are fixture-specific estimates, not universal thresholds; inspect the intervals.


## Explicit 512 MiB heap confirmation

| Protocol | Total bytes | Consumers | Eager CPU | Deferred CPU | CPU change | Eager → deferred allocation |
|---|---:|---|---:|---:|---:|---:|
| h1 | 400 | unread | 196.1 ± 7.7 | 135.1 ± 5.1 | -31.1% | 1456 → 1184 |
| h1 | 400 | both50 | 197.5 ± 10.6 | 181.3 ± 5.2 | -8.2% | 1456 → 1568 |
| h1 | 400 | both100 | 191.1 ± 10.3 | 213.1 ± 4.6 | +11.5% | 1456 → 1952 |
| h1 | 1,600 | unread | 623.0 ± 10.5 | 257.0 ± 3.5 | -58.7% | 5304 → 2528 |
| h1 | 1,600 | both50 | 635.8 ± 14.9 | 403.5 ± 6.6 | -36.5% | 5304 → 3808 |
| h1 | 1,600 | both100 | 629.4 ± 18.9 | 548.7 ± 19.0 | -12.8% | 5304 → 5088 |
| h1 | 8,192 | unread | 1885.5 ± 64.9 | 412.4 ± 7.4 | -78.1% | 22144 → 5856 |
| h1 | 8,192 | both50 | 1894.1 ± 30.1 | 779.8 ± 34.4 | -58.8% | 22144 → 12080 |
| h1 | 8,192 | both100 | 1901.7 ± 55.7 | 1112.1 ± 37.0 | -41.5% | 22144 → 18304 |
| h2 | 400 | unread | 133.9 ± 2.3 | 84.0 ± 6.7 | -37.2% | 1264 → 720 |
| h2 | 400 | both50 | 140.9 ± 4.5 | 130.0 ± 3.8 | -7.7% | 1264 → 1104 |
| h2 | 400 | both100 | 133.5 ± 3.7 | 175.7 ± 5.9 | +31.6% | 1264 → 1488 |
| h2 | 1,600 | unread | 489.0 ± 13.8 | 159.4 ± 5.6 | -67.4% | 5112 → 1008 |
| h2 | 1,600 | both50 | 490.2 ± 10.5 | 340.3 ± 11.7 | -30.6% | 5112 → 2288 |
| h2 | 1,600 | both100 | 495.2 ± 29.1 | 515.0 ± 8.6 | +4.0% | 5112 → 3568 |
| h2 | 8,192 | unread | 1154.4 ± 39.2 | 158.4 ± 5.3 | -86.3% | 21952 → 1008 |
| h2 | 8,192 | both50 | 1130.9 ± 71.9 | 552.3 ± 30.7 | -51.2% | 21952 → 7232 |
| h2 | 8,192 | both100 | 1127.0 ± 22.0 | 930.2 ± 49.3 | -17.5% | 21952 → 13456 |
