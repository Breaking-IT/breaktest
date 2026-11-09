# Locust version of the benchmark scenarios; run.sh passes the settings as environment variables.
#
# keepalive: BENCH_THREADS users, each pacing one GET every BENCH_PACING_MS on its own
#            kept-alive connection (same model as the BreakTest closed-model plans).
# newconn:   as keepalive, but every request asks the server to close the connection,
#            so the next request opens a new one.
#
# FastHttpUser (geventhttpclient) is Locust's fast client; the default HttpUser (requests)
# uses several times more CPU per request.
import os

from locust import FastHttpUser, constant_pacing, task

NEW_CONNECTION = os.environ.get("BENCH_SCENARIO") == "newconn"
PATH = os.environ.get("BENCH_PATH", "/")
PACING_SECONDS = int(os.environ.get("BENCH_PACING_MS", "40")) / 1000


class BenchUser(FastHttpUser):
    host = os.environ.get("BENCH_URL", "http://127.0.0.1:18080/").rstrip("/")
    wait_time = constant_pacing(PACING_SECONDS)
    insecure = True

    @task
    def get(self):
        headers = {"Connection": "close"} if NEW_CONNECTION else None
        with self.client.get(PATH, headers=headers, catch_response=True) as response:
            if response.status_code != 200 or not response.content:
                response.failure(f"unexpected response {response.status_code}")
