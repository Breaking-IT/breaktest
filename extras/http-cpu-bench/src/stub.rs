// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to you under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Minimal HTTP stub for CPU-per-request benchmarks.
//!
//! Serves a fixed body on a plain HTTP/1.1 port, a TLS HTTP/1.1 port and a TLS port that
//! offers HTTP/2 through ALPN (falling back to HTTP/1.1), and counts every request except
//! `GET /__stats`, which returns that count. The runner reads the counter before and
//! after the measurement window, so the request count does not depend on any load
//! generator's own reporting. `GET /__stats/h2` returns how many of them used HTTP/2.

use std::convert::Infallible;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use bytes::Bytes;
use http_body_util::Full;
use hyper::body::Incoming;
use hyper::header::{HeaderValue, CONTENT_ENCODING, CONTENT_TYPE};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;

/// The extractor scenario matches this token, so it must stay at the start of the body.
const BODY_PREFIX: &str = "token=bt-0123456789abcdef;\n";

struct Config {
    host: String,
    port: u16,
    tls_port: u16,
    h2_port: u16,
    body_bytes: usize,
    workers: usize,
}

struct State {
    gzip_body: Bytes,
    requests: AtomicU64,
    h2_requests: AtomicU64,
    body: Bytes,
}

fn usage() -> ! {
    eprintln!(
        "usage: bench-stub [--host 127.0.0.1] [--port 18080] [--tls-port 18443] [--h2-port 18444] \
         [--body-bytes 1024] [--workers 2]"
    );
    std::process::exit(2);
}

fn parse_args() -> Config {
    let mut config = Config {
        host: "127.0.0.1".to_owned(),
        port: 18080,
        tls_port: 18443,
        h2_port: 18444,
        body_bytes: 1024,
        workers: 2,
    };
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        let value = args.next().unwrap_or_else(|| usage());
        match flag.as_str() {
            "--host" => config.host = value,
            "--port" => config.port = value.parse().unwrap_or_else(|_| usage()),
            "--tls-port" => config.tls_port = value.parse().unwrap_or_else(|_| usage()),
            "--h2-port" => config.h2_port = value.parse().unwrap_or_else(|_| usage()),
            "--body-bytes" => config.body_bytes = value.parse().unwrap_or_else(|_| usage()),
            "--workers" => config.workers = value.parse().unwrap_or_else(|_| usage()),
            _ => usage(),
        }
    }
    config
}

/// JSON-like text, so the gzip variant compresses about as well as a typical API response.
fn make_body(size: usize) -> Bytes {
    let mut body = BODY_PREFIX.as_bytes().to_vec();
    let mut n = 0u32;
    while body.len() < size {
        n += 1;
        let line = format!(
            "{{\"id\":{n},\"name\":\"user-{n}\",\"email\":\"user{n}@example.com\",\"active\":{}}}\n",
            n % 3 != 0
        );
        body.extend_from_slice(line.as_bytes());
    }
    body.truncate(size.max(BODY_PREFIX.len()));
    Bytes::from(body)
}

fn gzip(body: &[u8]) -> Bytes {
    use std::io::Write;
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    encoder.write_all(body).expect("gzip");
    Bytes::from(encoder.finish().expect("gzip"))
}

fn tls_acceptor(alpn: &[&[u8]]) -> Result<TlsAcceptor, Box<dyn std::error::Error>> {
    let certified = rcgen::generate_simple_self_signed(vec!["localhost".to_owned(), "127.0.0.1".to_owned()])?;
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certified.key_pair.serialize_der()));
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_no_client_auth()
        .with_single_cert(vec![certified.cert.der().clone()], key)?;
    config.alpn_protocols = alpn.iter().map(|p| p.to_vec()).collect();
    // No session resumption: every new connection is a full handshake for every client,
    // like a new BreakTest user, whatever the client's own session cache does.
    config.session_storage = Arc::new(rustls::server::NoServerSessionStorage {});
    config.send_tls13_tickets = 0;
    Ok(TlsAcceptor::from(Arc::new(config)))
}

async fn handle(req: Request<Incoming>, state: Arc<State>) -> Result<Response<Full<Bytes>>, Infallible> {
    if req.uri().path() == "/__stats" {
        let count = state.requests.load(Ordering::Relaxed);
        return Ok(Response::new(Full::new(Bytes::from(format!("{count}\n")))));
    }
    if req.uri().path() == "/__stats/h2" {
        let count = state.h2_requests.load(Ordering::Relaxed);
        return Ok(Response::new(Full::new(Bytes::from(format!("{count}\n")))));
    }
    state.requests.fetch_add(1, Ordering::Relaxed);
    if req.version() == hyper::Version::HTTP_2 {
        state.h2_requests.fetch_add(1, Ordering::Relaxed);
    }
    // /gz serves the same body gzip-encoded, whatever the request's Accept-Encoding.
    let gzipped = req.uri().path() == "/gz";
    let mut response = Response::new(Full::new(if gzipped { state.gzip_body.clone() } else { state.body.clone() }));
    response
        .headers_mut()
        .insert(CONTENT_TYPE, HeaderValue::from_static("text/plain; charset=utf-8"));
    if gzipped {
        response
            .headers_mut()
            .insert(CONTENT_ENCODING, HeaderValue::from_static("gzip"));
    }
    Ok(response)
}

async fn serve_plain(listener: TcpListener, state: Arc<State>) {
    loop {
        let Ok((stream, _)) = listener.accept().await else { continue };
        let _ = stream.set_nodelay(true);
        let state = state.clone();
        tokio::spawn(async move {
            let service = service_fn(move |req| handle(req, state.clone()));
            let _ = http1::Builder::new()
                .keep_alive(true)
                .serve_connection(TokioIo::new(stream), service)
                .await;
        });
    }
}

async fn serve_tls(listener: TcpListener, acceptor: TlsAcceptor, state: Arc<State>) {
    loop {
        let Ok((stream, _)) = listener.accept().await else { continue };
        let _ = stream.set_nodelay(true);
        let acceptor = acceptor.clone();
        let state = state.clone();
        tokio::spawn(async move {
            let Ok(stream) = acceptor.accept(stream).await else { return };
            let service = service_fn(move |req| handle(req, state.clone()));
            let _ = http1::Builder::new()
                .keep_alive(true)
                .serve_connection(TokioIo::new(stream), service)
                .await;
        });
    }
}

async fn serve_h2(listener: TcpListener, acceptor: TlsAcceptor, state: Arc<State>) {
    loop {
        let Ok((stream, _)) = listener.accept().await else { continue };
        let _ = stream.set_nodelay(true);
        let acceptor = acceptor.clone();
        let state = state.clone();
        tokio::spawn(async move {
            let Ok(stream) = acceptor.accept(stream).await else { return };
            let service = service_fn(move |req| handle(req, state.clone()));
            // Serves whichever protocol ALPN agreed on.
            let _ = hyper_util::server::conn::auto::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(stream), service)
                .await;
        });
    }
}

async fn run(config: Config) -> Result<(), Box<dyn std::error::Error>> {
    let body = make_body(config.body_bytes);
    let state = Arc::new(State {
        gzip_body: gzip(&body),
        requests: AtomicU64::new(0),
        h2_requests: AtomicU64::new(0),
        body,
    });
    let plain = TcpListener::bind((config.host.as_str(), config.port)).await?;
    let tls = TcpListener::bind((config.host.as_str(), config.tls_port)).await?;
    let h2 = TcpListener::bind((config.host.as_str(), config.h2_port)).await?;
    // The TLS port speaks HTTP/1.1 only, so every client measures the same protocol there.
    let acceptor = tls_acceptor(&[b"http/1.1"])?;
    let h2_acceptor = tls_acceptor(&[b"h2", b"http/1.1"])?;
    println!(
        "bench-stub ready http://{host}:{port} https://{host}:{tls_port} h2=https://{host}:{h2_port} body={body}B gzip={gz}B workers={workers}",
        host = config.host,
        port = config.port,
        tls_port = config.tls_port,
        h2_port = config.h2_port,
        body = state.body.len(),
        gz = state.gzip_body.len(),
        workers = config.workers,
    );
    tokio::spawn(serve_plain(plain, state.clone()));
    tokio::spawn(serve_tls(tls, acceptor, state.clone()));
    tokio::spawn(serve_h2(h2, h2_acceptor, state));
    tokio::signal::ctrl_c().await?;
    Ok(())
}

fn main() {
    let config = parse_args();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(config.workers)
        .enable_all()
        .build()
        .expect("tokio runtime");
    if let Err(e) = runtime.block_on(run(config)) {
        eprintln!("bench-stub: {e}");
        std::process::exit(1);
    }
}
