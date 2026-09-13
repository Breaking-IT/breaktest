#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -euo pipefail
fixture_dir="${1:?Usage: create-fixture.sh output-directory}"
mkdir -p "$fixture_dir"
cd "$fixture_dir"
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 2 -subj '/CN=BreakTest HTTP3 Fixture CA'
for name in selfsigned expired wronghost server client; do
    cert_host=localhost
    if [[ "$name" == wronghost ]]; then cert_host=wrong.example; fi
    if [[ "$name" == client ]]; then cert_host=fixture-client; fi
    if [[ "$name" == server ]]; then cert_host=mtls.example.test; fi
    openssl req -new -newkey rsa:2048 -nodes -keyout "$name.key" -out "$name.csr" -subj "/CN=$cert_host"
    printf 'subjectAltName=DNS:%s\n' "$cert_host" > "$name.ext"
    if [[ "$name" == client ]]; then echo 'extendedKeyUsage=clientAuth' >> "$name.ext"; fi
    if [[ "$name" == server || "$name" == client ]]; then
        openssl x509 -req -in "$name.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -out "$name.pem" -days 2 -extfile "$name.ext"
    elif [[ "$name" == expired ]]; then
        keytool -genkeypair -alias expired -keyalg RSA -keystore expired.p12 -storetype PKCS12 -storepass password -keypass password -dname 'CN=localhost' -ext 'SAN=dns:localhost' -startdate '2020/01/01 00:00:00' -validity 1
        openssl pkcs12 -in expired.p12 -passin pass:password -nodes -nocerts -out expired.key
        openssl pkcs12 -in expired.p12 -passin pass:password -nokeys -out expired.pem
    else
        openssl x509 -req -in "$name.csr" -signkey "$name.key" -out "$name.pem" -days 2 -extfile "$name.ext"
    fi
done
openssl pkcs12 -export -in client.pem -inkey client.key -certfile ca.pem -name fixture-client -out client.p12 -passout pass:password
keytool -importcert -noprompt -alias fixture-ca -file ca.pem -keystore trust.jks -storetype JKS -storepass password
echo '127.0.0.1 localhost mtls.example.test' > hosts
cat > Caddyfile <<'CADDY'
{
    admin off
    auto_https off
    default_sni localhost
}
https://localhost:19443 {
    tls /fixture/selfsigned.pem /fixture/selfsigned.key
    respond "selfsigned"
}
https://localhost:19444 {
    tls /fixture/expired.pem /fixture/expired.key
    respond "expired"
}
https://localhost:19445 {
    tls /fixture/wronghost.pem /fixture/wronghost.key
    respond "wronghost"
}
https://mtls.example.test:19446 {
    tls /fixture/server.pem /fixture/server.key {
        client_auth {
            mode require_and_verify
            trust_pool file /fixture/ca.pem
        }
    }
    respond "mtls"
}
CADDY
