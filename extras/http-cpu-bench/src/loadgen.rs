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

//! Baseline load generator (HTTP/1.1 and HTTP/2): the floor for what a lean Rust engine pays
//! per request.
//!
//! It does the same work as the BreakTest plans (GET, read the whole body, count it) and
//! nothing else: no variables, timers, listeners or result objects. A gzip-encoded body is
//! decompressed like most clients do, unless `--no-decompress` leaves it encoded, which is
//! what BreakTest does when nothing reads the body. The gap between this and BreakTest is the
//! most a Rust engine could save on the same scenario.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use http_body_util::{BodyExt, Empty};
use hyper::client::conn::{http1, http2};
use hyper::header::{CONTENT_ENCODING, HOST};
use hyper::Request;
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;

type Error = Box<dyn std::error::Error + Send + Sync>;

struct Config {
    tls: bool,
    host: String,
    port: u16,
    path: String,
    rate: f64,
    connections: usize,
    duration: Duration,
    new_connection: bool,
    workers: usize,
    ramp_step: Option<Duration>,
    http2: bool,
    decompress: bool,
}

/// One connection's request handle, for whichever protocol it speaks.
enum Sender {
    H1(http1::SendRequest<Empty<Bytes>>),
    H2(http2::SendRequest<Empty<Bytes>>),
}

#[derive(Default)]
struct Stats {
    ok: AtomicU64,
    errors: AtomicU64,
    bytes: AtomicU64,
}

fn usage() -> ! {
    eprintln!(
        "usage: bench-loadgen --url http://127.0.0.1:18080/ [--rate 1000 (req/s, 0 = unlimited)] \
         [--connections 50] [--duration 60] [--new-connection] [--workers N] [--ramp-step-ms 1] \
         [--http2 (TLS only, via ALPN)] [--no-decompress]"
    );
    std::process::exit(2);
}

fn parse_url(url: &str) -> (bool, String, u16, String) {
    let (tls, rest) = if let Some(rest) = url.strip_prefix("https://") {
        (true, rest)
    } else if let Some(rest) = url.strip_prefix("http://") {
        (false, rest)
    } else {
        usage()
    };
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => (host, port.parse().unwrap_or_else(|_| usage())),
        None => (authority, if tls { 443 } else { 80 }),
    };
    (tls, host.to_owned(), port, path.to_owned())
}

fn parse_args() -> Config {
    let mut url = None;
    let mut config = Config {
        tls: false,
        host: String::new(),
        port: 0,
        path: String::new(),
        rate: 1000.0,
        connections: 50,
        duration: Duration::from_secs(60),
        new_connection: false,
        workers: std::thread::available_parallelism().map_or(4, |n| n.get()),
        ramp_step: None,
        http2: false,
        decompress: true,
    };
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        match flag.as_str() {
            "--new-connection" => {
                config.new_connection = true;
                continue;
            }
            "--http2" => {
                config.http2 = true;
                continue;
            }
            "--no-decompress" => {
                config.decompress = false;
                continue;
            }
            _ => {}
        }
        let value = args.next().unwrap_or_else(|| usage());
        match flag.as_str() {
            "--url" => url = Some(value),
            "--rate" => config.rate = value.parse().unwrap_or_else(|_| usage()),
            "--connections" => config.connections = value.parse().unwrap_or_else(|_| usage()),
            "--duration" => config.duration = Duration::from_secs(value.parse().unwrap_or_else(|_| usage())),
            "--workers" => config.workers = value.parse().unwrap_or_else(|_| usage()),
            "--ramp-step-ms" => {
                config.ramp_step = Some(Duration::from_secs_f64(value.parse::<f64>().unwrap_or_else(|_| usage()) / 1000.0))
            }
            _ => usage(),
        }
    }
    let (tls, host, port, path) = parse_url(&url.unwrap_or_else(|| usage()));
    config.tls = tls;
    config.host = host;
    config.port = port;
    config.path = path;
    config.connections = config.connections.max(1);
    if config.http2 && !config.tls {
        usage();
    }
    config
}

/// Accepts the stub's self-signed certificate but still checks handshake signatures,
/// so the crypto work matches a client that does verify.
#[derive(Debug)]
struct AcceptAnyCert(Arc<CryptoProvider>);

impl ServerCertVerifier for AcceptAnyCert {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

fn tls_connector(new_connection: bool, http2: bool) -> Result<TlsConnector, Error> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAnyCert(provider)))
        .with_no_client_auth();
    config.alpn_protocols = vec![if http2 { b"h2".to_vec() } else { b"http/1.1".to_vec() }];
    if new_connection {
        // A new BreakTest user starts with fresh TLS state, so every handshake is a full one.
        config.resumption = rustls::client::Resumption::disabled();
    }
    Ok(TlsConnector::from(Arc::new(config)))
}

async fn handshake<T>(io: T, http2: bool) -> Result<Sender, Error>
where
    T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    if http2 {
        let (sender, connection) = http2::handshake(TokioExecutor::new(), TokioIo::new(io)).await?;
        tokio::spawn(async move {
            let _ = connection.await;
        });
        return Ok(Sender::H2(sender));
    }
    let (sender, connection) = http1::handshake(TokioIo::new(io)).await?;
    tokio::spawn(async move {
        let _ = connection.await;
    });
    Ok(Sender::H1(sender))
}

async fn connect(config: &Config, tls: Option<&TlsConnector>) -> Result<Sender, Error> {
    let tcp = TcpStream::connect((config.host.as_str(), config.port)).await?;
    tcp.set_nodelay(true)?;
    if config.new_connection {
        // BreakTest closes a finished user's connections with HttpClient5 CloseMode.IMMEDIATE,
        // which resets them; do the same so neither side piles up TIME_WAIT sockets.
        tcp.set_zero_linger()?;
    }
    match tls {
        Some(connector) => {
            let name = ServerName::try_from(config.host.clone())?;
            handshake(connector.connect(name, tcp).await?, config.http2).await
        }
        None => handshake(tcp, false).await,
    }
}

async fn request(config: &Config, host_header: &str, sender: &mut Sender) -> Result<u64, Error> {
    let response = match sender {
        Sender::H1(sender) => {
            sender.ready().await?;
            let req = Request::get(config.path.as_str())
                .header(HOST, host_header)
                .body(Empty::new())?;
            sender.send_request(req).await?
        }
        Sender::H2(sender) => {
            sender.ready().await?;
            // HTTP/2 takes scheme and authority from an absolute URI instead of a Host header.
            let req = Request::get(format!("https://{host_header}{}", config.path)).body(Empty::new())?;
            sender.send_request(req).await?
        }
    };
    let ok = response.status().is_success();
    let gzipped = response
        .headers()
        .get(CONTENT_ENCODING)
        .is_some_and(|value| value.as_bytes().eq_ignore_ascii_case(b"gzip"));
    let body = response.into_body().collect().await?.to_bytes();
    if !ok {
        return Err("non-2xx response".into());
    }
    if gzipped && config.decompress {
        let mut decoded = Vec::with_capacity(body.len() * 8);
        std::io::Read::read_to_end(&mut flate2::read::GzDecoder::new(&body[..]), &mut decoded)?;
        return Ok(decoded.len() as u64);
    }
    Ok(body.len() as u64)
}

async fn worker(
    id: usize,
    config: Arc<Config>,
    tls: Option<Arc<TlsConnector>>,
    stats: Arc<Stats>,
    start: Instant,
) {
    let deadline = start + config.duration;
    let host_header = format!("{}:{}", config.host, config.port);
    let interval = (config.rate > 0.0).then(|| Duration::from_secs_f64(config.connections as f64 / config.rate));
    // Spread the connections over one interval so requests do not arrive in bursts, unless
    // --ramp-step-ms starts them like a thread group ramp-up (one every step).
    let mut next = interval.map(|i| match config.ramp_step {
        Some(step) => start + step * id as u32,
        None => start + i.mul_f64(id as f64 / config.connections as f64),
    });
    let mut sender: Option<Sender> = None;
    loop {
        match (next.as_mut(), interval) {
            (Some(at), Some(step)) => {
                if *at >= deadline {
                    break;
                }
                tokio::time::sleep_until((*at).into()).await;
                *at += step;
            }
            _ if Instant::now() >= deadline => break,
            _ => {}
        }
        if sender.is_none() {
            match connect(&config, tls.as_deref()).await {
                Ok(s) => sender = Some(s),
                Err(_) => {
                    stats.errors.fetch_add(1, Ordering::Relaxed);
                    continue;
                }
            }
        }
        let current = sender.as_mut().expect("connected");
        match request(&config, &host_header, current).await {
            Ok(bytes) => {
                stats.ok.fetch_add(1, Ordering::Relaxed);
                stats.bytes.fetch_add(bytes, Ordering::Relaxed);
            }
            Err(_) => {
                stats.errors.fetch_add(1, Ordering::Relaxed);
                sender = None;
            }
        }
        if config.new_connection {
            sender = None;
        }
    }
}

async fn run(config: Config) -> Result<(), Error> {
    let config = Arc::new(config);
    let tls = if config.tls {
        Some(Arc::new(tls_connector(config.new_connection, config.http2)?))
    } else {
        None
    };
    let stats = Arc::new(Stats::default());
    println!(
        "bench-loadgen start {}://{}:{}{} rate={} connections={} duration={}s new_connection={} workers={} http2={} decompress={}",
        if config.tls { "https" } else { "http" },
        config.host,
        config.port,
        config.path,
        config.rate,
        config.connections,
        config.duration.as_secs(),
        config.new_connection,
        config.workers,
        config.http2,
        config.decompress,
    );
    let start = Instant::now();
    let tasks: Vec<_> = (0..config.connections)
        .map(|id| tokio::spawn(worker(id, config.clone(), tls.clone(), stats.clone(), start)))
        .collect();
    for task in tasks {
        let _ = task.await;
    }
    let elapsed = start.elapsed().as_secs_f64();
    let ok = stats.ok.load(Ordering::Relaxed);
    println!(
        "bench-loadgen done ok={ok} errors={} bytes={} elapsed={elapsed:.1}s rate={:.0}/s",
        stats.errors.load(Ordering::Relaxed),
        stats.bytes.load(Ordering::Relaxed),
        ok as f64 / elapsed,
    );
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
        eprintln!("bench-loadgen: {e}");
        std::process::exit(1);
    }
}
