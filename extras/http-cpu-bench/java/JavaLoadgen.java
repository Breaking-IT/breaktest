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

import java.net.URI;
import java.security.Provider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import java.util.concurrent.locks.LockSupport;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.compat.ClassicToAsyncAdaptor;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * Baseline load generator on the JVM: the same HttpClient5 library BreakTest uses, without
 * the BreakTest engine. One shared client, one virtual thread per connection, each doing a
 * GET and reading the whole body. The gap to BreakTest is engine overhead; the gap to the
 * Rust client is what the JVM and HttpClient5 cost.
 *
 * <p>Run with BreakTest's jars on the class path, as run.sh does:
 * {@code java -cp 'lib/*' JavaLoadgen.java --url http://127.0.0.1:18080/ --rate 2000}
 */
public final class JavaLoadgen {

    private record Config(URI uri, double rate, int connections, Duration duration,
            boolean newConnection, String client, long rampStepNanos, String tlsProvider) {
    }

    @FunctionalInterface
    private interface Get {
        long get() throws Exception;
    }

    private JavaLoadgen() {
    }

    private static void usage() {
        System.err.println("usage: JavaLoadgen --url http://127.0.0.1:18080/ [--rate 1000 (req/s, 0 = unlimited)]"
                + " [--connections 50] [--duration 60] [--new-connection] [--ramp-step-ms 5] [--tls-provider jdk|conscrypt] [--client async|classic|async-per-user|async-per-user-adaptor]");
        System.exit(2);
    }

    private static Config parse(String[] args) {
        URI uri = null;
        double rate = 1000;
        int connections = 50;
        long duration = 60;
        boolean newConnection = false;
        String client = "async";
        long rampStepNanos = -1;
        String tlsProvider = "jdk";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--new-connection")) {
                newConnection = true;
                continue;
            }
            if (i + 1 >= args.length) {
                usage();
            }
            String value = args[++i];
            switch (args[i - 1]) {
                case "--url" -> uri = URI.create(value);
                case "--rate" -> rate = Double.parseDouble(value);
                case "--connections" -> connections = Math.max(1, Integer.parseInt(value));
                case "--duration" -> duration = Long.parseLong(value);
                case "--client" -> client = value;
                case "--ramp-step-ms" -> rampStepNanos = (long) (Double.parseDouble(value) * 1e6);
                case "--tls-provider" -> tlsProvider = value;
                default -> usage();
            }
        }
        if (uri == null || !List.of("async", "classic", "async-per-user", "async-per-user-adaptor").contains(client)) {
            usage();
        }
        if (!List.of("jdk", "conscrypt").contains(tlsProvider)) {
            usage();
        }
        return new Config(uri, rate, connections, Duration.ofSeconds(duration), newConnection, client, rampStepNanos,
                tlsProvider);
    }

    private static SSLContext trustAll(Config config) throws Exception {
        var builder = SSLContexts.custom().loadTrustMaterial(TrustAllStrategy.INSTANCE);
        if (config.tlsProvider().equals("conscrypt")) {
            // Conscrypt (BoringSSL) must be on the class path; loaded reflectively so the JDK
            // path needs nothing extra.
            builder.setProvider((Provider) Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("newProvider").invoke(null));
        }
        return builder.build();
    }

    // In new-connection mode connections are never reused and close with a reset, as BreakTest
    // does for a finished user (CloseMode.IMMEDIATE), so no TIME_WAIT sockets pile up.
    private static ConnectionReuseStrategy reuse(Config config) {
        return config.newConnection() ? (request, response, context) -> false : DefaultConnectionReuseStrategy.INSTANCE;
    }

    private static Get classic(Config config, List<AutoCloseable> closeables) throws Exception {
        SocketConfig.Builder socket = SocketConfig.custom().setTcpNoDelay(true);
        if (config.newConnection()) {
            socket.setSoLinger(TimeValue.ZERO_MILLISECONDS);
        }
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                                .setSslContext(trustAll(config))
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                .buildClassic())
                        .setDefaultSocketConfig(socket.build())
                        .setMaxConnTotal(config.connections())
                        .setMaxConnPerRoute(config.connections())
                        .build())
                .setConnectionReuseStrategy(reuse(config))
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableRedirectHandling()
                .build();
        closeables.add(client);
        return () -> client.execute(new HttpGet(config.uri()), response -> {
            byte[] body = EntityUtils.toByteArray(response.getEntity());
            if (response.getCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.getCode());
            }
            return (long) body.length;
        });
    }

    private static Get async(Config config, List<AutoCloseable> closeables) throws Exception {
        return async(config, closeables, false, false);
    }

    /**
     * {@code perUser} mirrors BreakTest's HTTPHC5H2Impl: every user gets its own async client
     * with a single I/O reactor running on a virtual thread, instead of all users sharing one
     * client whose reactors run on one platform thread per core. {@code adaptor} also drives
     * the client through HttpClient5's ClassicToAsyncAdaptor, as HTTPHC5H2Impl does.
     */
    private static Get async(Config config, List<AutoCloseable> closeables, boolean perUser, boolean adaptor)
            throws Exception {
        IOReactorConfig.Builder reactor = IOReactorConfig.custom().setTcpNoDelay(true);
        if (perUser) {
            reactor.setIoThreadCount(1);
        }
        if (config.newConnection()) {
            reactor.setSoLinger(TimeValue.ZERO_MILLISECONDS);
        }
        CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setTlsStrategy(ClientTlsStrategyBuilder.create()
                                .setSslContext(trustAll(config))
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                .buildAsync())
                        // Same policy as BreakTest's default (blank HTTP version) sampler.
                        .setDefaultTlsConfig(TlsConfig.custom().setVersionPolicy(HttpVersionPolicy.NEGOTIATE).build())
                        .setMaxConnTotal(config.connections())
                        .setMaxConnPerRoute(config.connections())
                        .build())
                .setIOReactorConfig(reactor.build())
                .setThreadFactory(perUser ? VIRTUAL_REACTOR_THREADS : new DefaultThreadFactory("httpclient-dispatch", true))
                .setConnectionReuseStrategy(reuse(config))
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableRedirectHandling()
                .build();
        client.start();
        synchronized (closeables) {
            closeables.add(client);
        }
        if (adaptor) {
            ClassicToAsyncAdaptor classic = new ClassicToAsyncAdaptor(client, Timeout.ofMinutes(1));
            return () -> classic.execute(new HttpGet(config.uri()), response -> {
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                if (response.getCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.getCode());
                }
                return (long) body.length;
            });
        }
        return () -> {
            SimpleHttpRequest request = SimpleRequestBuilder.get(config.uri()).build();
            SimpleHttpResponse response = client.execute(request, null).get();
            if (response.getCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.getCode());
            }
            byte[] body = response.getBodyBytes();
            return body == null ? 0 : body.length;
        };
    }

    private static final ThreadFactory VIRTUAL_REACTOR_THREADS = Thread.ofVirtual().name("reactor-", 0).factory();

    public static void main(String[] args) throws Exception {
        Config config = parse(args);
        List<AutoCloseable> closeables = new ArrayList<>();
        IntFunction<Get> gets;
        if (config.client().startsWith("async-per-user")) {
            boolean adaptor = config.client().endsWith("-adaptor");
            gets = id -> {
                try {
                    return async(config, closeables, true, adaptor);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
        } else {
            Get shared = config.client().equals("classic") ? classic(config, closeables) : async(config, closeables);
            gets = id -> shared;
        }
        System.out.printf(Locale.ROOT,
                "java-loadgen start %s rate=%s connections=%d duration=%ds new_connection=%s client=%s tls=%s%n",
                config.uri(), config.rate(), config.connections(), config.duration().toSeconds(),
                config.newConnection(), config.client(), config.tlsProvider());

        LongAdder ok = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder bytes = new LongAdder();
        long start = System.nanoTime();
        long deadline = start + config.duration().toNanos();
        long interval = config.rate() > 0 ? (long) (1e9 * config.connections() / config.rate()) : 0;
        List<Thread> workers = new ArrayList<>();
        for (int id = 0; id < config.connections(); id++) {
            // Spread the connections over one interval so requests do not arrive in bursts, unless
            // --ramp-step-ms starts them like a thread group ramp-up (one every step), which can
            // leave several connections on the same phase of the interval.
            long first = config.rampStepNanos() >= 0
                    ? start + config.rampStepNanos() * id
                    : start + interval * id / config.connections();
            int workerId = id;
            workers.add(Thread.ofVirtual().name("loadgen-" + id).start(() -> {
                Get get = gets.apply(workerId);
                long next = first;
                while (true) {
                    if (interval > 0) {
                        if (next >= deadline) {
                            break;
                        }
                        long wait;
                        while ((wait = next - System.nanoTime()) > 0) {
                            LockSupport.parkNanos(wait);
                        }
                        next += interval;
                    } else if (System.nanoTime() >= deadline) {
                        break;
                    }
                    try {
                        bytes.add(get.get());
                        ok.increment();
                    } catch (Exception e) {
                        errors.increment();
                    }
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "java-loadgen done ok=%d errors=%d bytes=%d elapsed=%.1fs rate=%.0f/s%n",
                ok.sum(), errors.sum(), bytes.sum(), elapsed, ok.sum() / elapsed);
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }
}
