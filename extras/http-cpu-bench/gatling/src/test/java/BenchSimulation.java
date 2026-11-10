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

import static io.gatling.javaapi.core.CoreDsl.bodyBytes;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.Duration;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * Gatling version of the benchmark scenarios; run.sh passes the settings as system properties.
 *
 * <p>keepalive: {@code bench.threads} users, each pacing one GET every {@code bench.pacing_ms}
 * on its own kept-alive connection (same model as the BreakTest closed-model plans).
 * newconn: {@code bench.rate} arrivals per second, one request per user, so every request
 * opens a new connection.
 */
public class BenchSimulation extends Simulation {

    private static final String URL = System.getProperty("bench.url", "http://127.0.0.1:18080/");
    private static final boolean NEW_CONNECTION = "newconn".equals(System.getProperty("bench.scenario"));
    private static final int THREADS = Integer.getInteger("bench.threads", 200);
    private static final int RATE = Integer.getInteger("bench.rate", 5000);
    private static final Duration DURATION = Duration.ofSeconds(Integer.getInteger("bench.duration", 30));
    private static final Duration PACING = Duration.ofMillis(Integer.getInteger("bench.pacing_ms", 40));

    private static final HttpProtocolBuilder PROTOCOL = protocol();

    private static HttpProtocolBuilder protocol() {
        HttpProtocolBuilder protocol = http.baseUrl(URL)
                .disableCaching()
                .disableWarmUp();
        // HTTP/2 is off by default in Gatling; run.sh turns it on for the h2 port.
        return Boolean.getBoolean("bench.http2") ? protocol.enableHttp2() : protocol;
    }

    private static final ChainBuilder GET = http("GET /").get("")
            .check(status().is(200), bodyBytes().exists())
            .toChainBuilder();

    {
        PopulationBuilder population = NEW_CONNECTION
                ? scenario("newconn").exec(GET).injectOpen(constantUsersPerSec(RATE).during(DURATION))
                : scenario("keepalive")
                        .exec(during(DURATION).on(pace(PACING).exec(GET)))
                        // One user per millisecond, like the other tools' ramp-up.
                        .injectOpen(rampUsers(THREADS).during(Duration.ofMillis(THREADS)));
        setUp(population.protocols(PROTOCOL));
    }
}
