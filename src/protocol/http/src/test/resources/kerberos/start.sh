#!/bin/sh
# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.
set -eu
cat > /etc/krb5.conf <<'EOF'
[libdefaults]
 default_realm = BREAKTEST.TEST
 dns_lookup_kdc = false
 dns_lookup_realm = false
 rdns = false
[realms]
 BREAKTEST.TEST = {
  kdc = 127.0.0.1
 }
EOF
kdb5_util create -s -P fixture-master
kadmin.local -q 'addprinc -pw fixture-password +requires_preauth tester@BREAKTEST.TEST'
kadmin.local -q 'addprinc -pw other-password +requires_preauth other@BREAKTEST.TEST'
kadmin.local -q 'addprinc -randkey HTTP/localhost@BREAKTEST.TEST'
kadmin.local -q 'ktadd -k /tmp/http.keytab HTTP/localhost@BREAKTEST.TEST'
krb5kdc -n &
export KRB5_KTNAME=/tmp/http.keytab
exec /usr/bin/python3 /fixture/server.py
