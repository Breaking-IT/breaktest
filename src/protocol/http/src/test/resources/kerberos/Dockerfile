# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.
FROM python:3.13-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends krb5-kdc krb5-admin-server python3-gssapi && rm -rf /var/lib/apt/lists/*
COPY server.py start.sh /fixture/
ENTRYPOINT ["sh", "/fixture/start.sh"]
