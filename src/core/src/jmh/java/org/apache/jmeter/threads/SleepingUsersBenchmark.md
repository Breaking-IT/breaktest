# Sleeping 10,000 users: 2026-09-28

## Result

All 10,000 virtual users remained asleep throughout the full-duration-sleep measurements: zero delay returns, zero new delay entries, and all workers TIMED_WAITING before and after every window. Median process CPU was close to the empty-JVM baseline on both Java versions. Sleeping users therefore consumed practically no steady CPU in this test. This does not claim literally zero cost at sleep entry, wake-up, or request execution.

The implementation removes periodic per-user polling from timer, transaction think-time and pacing delays. Workers sleep until their deadline, shortened by a scheduled test end when applicable. Stop and fork cancellation explicitly wake registered sleeping workers. Registration/removal use short synchronized sections; the sleep itself holds no monitor.

| Java | Mode | Median CPU, % of one core | Observed range | Delay returns / 10 s |
|---|---|---:|---:|---:|
| 21.0.5 | Empty JVM | 0.0694% | 0.0646–0.0818% | 0 |
| 21.0.5 | Full sleep, explicit cancellation | 0.0653% | 0.0521–0.0849% | 0 |
| 21.0.5 | Simulated 1 s polling | 7.5155% | 6.2367–7.9447% | 100000 |
| 21.0.5 | Plain Thread.sleep | 0.0592% | 0.0569–0.1387% | 0 |
| 26.0.1 | Empty JVM | 0.0910% | 0.0894–0.1017% | 0 |
| 26.0.1 | Full sleep, explicit cancellation | 0.0978% | 0.0912–0.3190% | 0 |
| 26.0.1 | Simulated 1 s polling | 6.2063% | 5.6875–15.1163% | 100000 |
| 26.0.1 | Plain Thread.sleep | 0.1164% | 0.0946–0.1319% | 0 |

Full-sleep heap occupancy was approximately 116 MiB for this minimal 10,000-user setup, versus approximately 115 MiB for plain sleep. This is Java heap occupancy, not total process memory or a prediction for a real test plan. No GC occurred in full-sleep or plain-sleep measurement windows. The Java 26 polling case had one collection.

## Method and limitations

- macOS arm64, 14 logical processors, 48 GiB RAM; Java 21.0.5 and 26.0.1. Separate JVM for each case, 512 MiB initial / 2 GiB maximum heap, no concurrent Gradle build.
- Real JMeterThread.run workers on virtual threads, each entering a TransactionController with ten minutes of fixed think time. No HTTP traffic, GUI, or full engine scheduler. This measures idle virtual users, not throughput or end-to-end test-plan capacity.
- All users enter the delay before explicit GC and two seconds of settling. Three ten-second windows follow. Startup and shutdown are excluded, as are synchronous state probes and reporting. Process CPU still includes background JVM work, including any asynchronous work caused by prior activity.
- CPU percentage is process CPU time divided by elapsed time, multiplied by 100: 100% means one fully occupied core. Values are medians and observed ranges, not confidence intervals. One JVM per case and three windows are exploratory evidence, not independent replication.
- `signalled` calls the actual full-duration awaitDelay implementation. `plain` substitutes Thread.sleep without registration. `polling` caps each actual awaitDelay call at 1,000 ms, recreating repeated wake-ups in the transaction loop. It is a simulation, not an old-build measurement; shared atomic entry/return counters also add work to the polling case. Its CPU numbers should not be interpreted as an exact production regression percentage.
- Both non-polling modes assert zero delay entries/returns during each window and all 10,000 workers sleeping at both boundaries. They do not assert that JVM carrier threads never wake internally.
- Earlier short-delay measurements in IdleDelayTargetedBenchmark.md address delay-boundary cost. Removing polling avoids repeatedly paying that cost during a long think time; genuine short repeated delays still have scheduling and registration costs.

## Reproduce

From the repository root:

    ./gradlew :src:core:compileJmhJava createDist

Run each command in a separate JVM, using the Java executable to compare:

    java -Xms512m -Xmx2g -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.SleepingUsersBenchmark 0 plain 10 3
    java -Xms512m -Xmx2g -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.SleepingUsersBenchmark 10000 signalled 10 3
    java -Xms512m -Xmx2g -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.SleepingUsersBenchmark 10000 polling 10 3
    java -Xms512m -Xmx2g -Djava.awt.headless=true -cp 'src/core/build/classes/java/jmh:lib/*:lib/ext/*' org.apache.jmeter.threads.SleepingUsersBenchmark 10000 plain 10 3

Arguments: users, mode, seconds per window, windows. Local raw CSV and process logs: build/reports/benchmarks/sleeping-users/.

## Correctness

Core tests cover prompt cancellation of transaction, timer and pacing delays, full-duration delay requests, scheduled deadlines, parallel delay workers, external interrupts, and preventing stop interrupts from leaking into later work. The engine regression verifies graceful stop preserves an active sampler. Full core checks passed: 1,102 tests passed, 3 skipped.
