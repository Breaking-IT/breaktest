<!--
Copyright 2024-2026 BreakTest contributors

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
-->

# HTTP/3 certificate integration tests

Requires Java 26, Docker, OpenSSL, and keytool. Generate disposable certificates
in a fresh directory; nothing is installed into the machine's trust store:

```sh
bash src/protocol/http/src/test/resources/http3/create-fixture.sh /tmp/breaktest-h3-fixture
docker run --rm -d --name breaktest-h3-review \
  -p 127.0.0.1:19443-19446:19443-19446/tcp \
  -p 127.0.0.1:19443-19446:19443-19446/udp \
  -v /tmp/breaktest-h3-fixture:/fixture:ro \
  caddy:2@sha256:13ba145cba2f3e28fa801994876e4c086d1b95d5aa2a520a734765ffb6b12017 \
  caddy run --config /fixture/Caddyfile
export BREAKTEST_HTTP3_FIXTURE=/tmp/breaktest-h3-fixture
export BREAKTEST_HTTP3_SELF_SIGNED_URL=https://localhost:19443/
./gradlew :src:protocol:http:test --tests '*TestHTTPJavaHttp3Impl' \
  :src:core:test --tests '*UpdateService*Test' -PjdkTestVersion=26 \
  -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=true
./gradlew :src:protocol:http:test --tests '*HTTP3MutualTlsTest' \
  -PjdkTestVersion=26 -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=true \
  -Djmeter.properties.jdk.net.hosts.file="$BREAKTEST_HTTP3_FIXTURE/hosts"
./gradlew :src:protocol:http:test \
  --tests '*TestHTTPJavaHttp3Impl.embeddedClientWithoutStartupSettingRejectsWrongHostname' \
  -PjdkTestVersion=26 -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=false
docker stop breaktest-h3-review
```

The ports serve a self-signed certificate (19443), an expired certificate
(19444), a wrong-host certificate (19445), and a trusted server requiring a
client certificate (19446). The first three assert direct H3 and H2 → H3 → H3.
Run the mTLS test separately: it initializes TLS without default client keys,
then configures JMeter's key/trust stores and disables certificate retries
before the sampler class initializes. The updater test trusts its local server
certificate and proves an ordinary JDK client succeeds while the actual updater
client rejects the hostname, with the global hostname switch enabled.

The embedded regression deliberately omits the permissive startup setting and
expects a wrong-host failure. It documents the JDK initialization requirement,
not a different sampler setting. CI runs the regular tests on Java 21 and 26,
with these Docker integration tests on 26.
