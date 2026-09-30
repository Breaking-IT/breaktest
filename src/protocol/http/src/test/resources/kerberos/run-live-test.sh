#!/usr/bin/env bash
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(git -C "$script_dir" rev-parse --show-toplevel)"
fixture_dir="$(mktemp -d)"
container_id=""
frontend_id=""
cleanup() {
    if [[ -n "$frontend_id" ]]; then
        docker logs "$frontend_id"
        docker rm -f "$frontend_id" >/dev/null
    fi
    if [[ -n "$container_id" ]]; then
        docker logs "$container_id"
        docker rm -f "$container_id" >/dev/null
    fi
    rm -rf "$fixture_dir"
}
trap cleanup EXIT
docker build -t breaktest-kerberos-fixture "$script_dir"
container_id="$(docker run -d --rm -p 127.0.0.1::88/tcp -p 127.0.0.1::8080/tcp -p 127.0.0.1::8443/tcp breaktest-kerberos-fixture)"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost \
    -keyout "$fixture_dir/server.key" -out "$fixture_dir/server.crt" 2>/dev/null
cat > "$fixture_dir/Caddyfile" <<'EOF'
{
    admin off
    auto_https off
    servers {
        protocols h1 h2
    }
}
:8443 {
    tls /fixture/server.crt /fixture/server.key
    reverse_proxy 127.0.0.1:8080 {
        header_up X-Fixture-Frontend {http.request.proto}
    }
}
EOF
frontend_id="$(docker run -d --rm --network "container:$container_id" \
    --mount "type=bind,src=$fixture_dir,dst=/fixture,readonly" \
    caddy:2 caddy run --config /fixture/Caddyfile --adapter caddyfile)"
kdc_port="$(docker port "$container_id" 88/tcp | awk -F: '{print $NF}')"
http_port="$(docker port "$container_id" 8080/tcp | awk -F: '{print $NF}')"
printf '%s\n' "$http_port" > "$fixture_dir/http-port"
https_port="$(docker port "$container_id" 8443/tcp | awk -F: '{print $NF}')"
printf '%s\n' "$https_port" > "$fixture_dir/https-port"
cat > "$fixture_dir/krb5.conf" <<EOF
[libdefaults]
 default_realm = BREAKTEST.TEST
 dns_lookup_kdc = false
 dns_lookup_realm = false
 rdns = false
 udp_preference_limit = 1
[realms]
 BREAKTEST.TEST = {
  kdc = 127.0.0.1:$kdc_port
 }
EOF
cat > "$fixture_dir/jaas.conf" <<'EOF'
JMeter {
 com.sun.security.auth.module.Krb5LoginModule required
 useTicketCache=false
 doNotPrompt=false
 refreshKrb5Config=true;
};
EOF
ready=false
for attempt in {1..30}; do
    if curl -fsS -o /dev/null "http://localhost:$http_port/public" && curl -kfsS -o /dev/null "https://localhost:$https_port/public"; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo 'Kerberos fixture did not start' >&2
    exit 1
fi
cd "$repo_dir"
BREAKTEST_KERBEROS_FIXTURE="$fixture_dir" ./gradlew :src:protocol:http:test \
    --tests 'org.apache.jmeter.protocol.http.sampler.*Kerberos*' "$@"
