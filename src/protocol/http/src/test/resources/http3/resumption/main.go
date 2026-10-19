// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Local-only QUIC fixture. Reports server-observed connection IDs and TLS
// resumption, and exposes a control endpoint to close individual connections.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"golang.org/x/net/http2"
)

type connectionKey struct{}
type connection struct {
	id   int64
	conn *quic.Conn
}

func main() {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "localhost"}, DNSNames: []string{"localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	if len(os.Args) > 2 && os.Args[2] == "coalesce" {
		template.DNSNames = []string{"a.test", "b.test", "reject.test", "dns.test"}
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	must(err)
	must(os.WriteFile(os.Args[1], pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600))
	config := &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS13}
	if len(os.Args) > 2 && (os.Args[2] == "h2" || os.Args[2] == "coalesce") {
		serveH2(config)
		return
	}
	// Reserve TCP first, then UDP on the same port; retry if that UDP port is busy.
	var tcp net.Listener
	var udp net.PacketConn
	for {
		tcp, err = net.Listen("tcp", "127.0.0.1:0")
		must(err)
		udp, err = net.ListenPacket("udp", tcp.Addr().String())
		if err == nil {
			break
		}
		tcp.Close()
	}
	control, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	var tcpIDs atomic.Int64
	// TCP discovery endpoint also serves the certificate probe.
	tcpServer := &http.Server{TLSConfig: config.Clone(), ConnContext: func(ctx context.Context, c net.Conn) context.Context {
		return context.WithValue(ctx, connectionKey{}, tcpIDs.Add(1))
	}, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if len(os.Args) < 3 || os.Args[2] != "no-alt" && os.Args[2] != "no-alt-h1" {
			w.Header().Set("Alt-Svc", fmt.Sprintf(`h3=":%d"; ma=3600`, udp.LocalAddr().(*net.UDPAddr).Port))
		}
		time.Sleep(300 * time.Millisecond)
		fmt.Fprintf(w, "%d", r.Context().Value(connectionKey{}).(int64))
	})}
	// Delay TCP TLS and response independently, leaving the QUIC fixture unchanged.
	tcpServer.TLSConfig.GetConfigForClient = func(*tls.ClientHelloInfo) (*tls.Config, error) {
		time.Sleep(100 * time.Millisecond)
		return nil, nil
	}
	if len(os.Args) < 3 || os.Args[2] != "h1" && os.Args[2] != "no-alt-h1" {
		must(http2.ConfigureServer(tcpServer, &http2.Server{}))
	}
	go func() { must(tcpServer.Serve(tls.NewListener(tcp, tcpServer.TLSConfig))) }()
	var ids atomic.Int64
	var connections sync.Map
	server := &http3.Server{TLSConfig: config, ConnContext: func(ctx context.Context, c *quic.Conn) context.Context {
		state := &connection{ids.Add(1), c}
		connections.Store(state.id, state)
		return context.WithValue(ctx, connectionKey{}, state)
	}, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c := r.Context().Value(connectionKey{}).(*connection)
		fmt.Fprintf(w, "%d,%t", c.id, r.TLS.DidResume)
	})}
	go func() { must(server.Serve(udp)) }()
	fmt.Printf("%d %d\n", udp.LocalAddr().(*net.UDPAddr).Port, control.Addr().(*net.TCPAddr).Port)
	must(http.Serve(control, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
		if err != nil {
			http.Error(w, "id", 400)
			return
		}
		value, ok := connections.LoadAndDelete(id)
		if !ok {
			http.Error(w, "connection", 404)
			return
		}
		must(value.(*connection).conn.CloseWithError(0, "test reconnect"))
		w.WriteHeader(200)
	})))
}
func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}

// TCP HTTP/2 counterpart, sharing the certificate and server-observed assertions.
func serveH2(config *tls.Config) {
	address := "127.0.0.1:0"
	coalesce := len(os.Args) > 2 && os.Args[2] == "coalesce"
	if coalesce {
		address = "[::]:0"
	}
	var rejected atomic.Int64
	tcp, err := net.Listen("tcp", address)
	must(err)
	control, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	var ids atomic.Int64
	var connections sync.Map
	server := &http.Server{TLSConfig: config, ConnContext: func(ctx context.Context, c net.Conn) context.Context {
		id := ids.Add(1)
		connections.Store(id, c)
		return context.WithValue(ctx, connectionKey{}, id)
	}, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 {
			http.Error(w, "Expected HTTP/2", 400)
			return
		}
		if coalesce {
			host := strings.Split(r.Host, ":")[0]
			if host == "reject.test" && r.TLS.ServerName != host {
				rejected.Add(1)
				w.WriteHeader(421)
				return
			}
			w.Header().Set("X-Rejected", strconv.FormatInt(rejected.Load(), 10))
			time.Sleep(100 * time.Millisecond)
			body, _ := io.ReadAll(r.Body)
			fmt.Fprintf(w, "%d,%t,%s,%s", r.Context().Value(connectionKey{}).(int64), r.TLS.DidResume, host, body)
			return
		}
		fmt.Fprintf(w, "%d,%t", r.Context().Value(connectionKey{}).(int64), r.TLS.DidResume)
	})}
	must(http2.ConfigureServer(server, &http2.Server{}))
	go func() { must(server.Serve(tls.NewListener(tcp, server.TLSConfig))) }()
	fmt.Printf("%d %d\n", tcp.Addr().(*net.TCPAddr).Port, control.Addr().(*net.TCPAddr).Port)
	must(http.Serve(control, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
		if err != nil {
			http.Error(w, "id", 400)
			return
		}
		c, ok := connections.LoadAndDelete(id)
		if !ok {
			http.Error(w, "connection", 404)
			return
		}
		// No requests are in flight when the test asks for a reconnect.
		// Announce a graceful HTTP/2 shutdown before TLS close_notify.
		conn := c.(net.Conn)
		must(http2.NewFramer(conn, nil).WriteGoAway(0x7fffffff, http2.ErrCodeNo, nil))
		conn.Close()
		w.WriteHeader(200)
	})))
}
