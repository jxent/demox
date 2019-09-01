/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.ConnectionSpec;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http1.Http1Codec;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.tls.OkHostnameVerifier;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;

public final class RealConnection extends Http2Connection.Listener implements Connection {
    /**
     * 关联的StreamAllocation列表, 用来统计在一个连接上建立了哪些流，
     * 通过StreamAllocation的acquire方法和release方法可以将一个allocation对象添加到此链表或者移除此链表
     */
    public final List<Reference<StreamAllocation>> allocations = new ArrayList<>();
    private final Route route;
    /**
     * The application layer socket. Either an {@link SSLSocket} layered over {@link #rawSocket}, or
     * {@link #rawSocket} itself if this connection does not use SSL.
     *
     * 应用层socket，或者是在rawSocket层之上的SSLSocket对象，或者就是这个tcp层的rawSocket
     */
    public Socket socket;
    public volatile Http2Connection http2Connection;
    public int successCount;    // 成功的次数
    public BufferedSource source;   // source sink 输入输出流
    public BufferedSink sink;
    public int allocationLimit;     // 此链接可以承载最大并发流的限制，如果不超过限制，可以随意增加
    public boolean noNewStreams;    // 可以简单理解为它表示该连接不可用。这个值一旦被设为true,则这个connection便不会再创建stream。
    public long idleAtNanos = Long.MAX_VALUE;
    /**
     * The low-level TCP socket. Tcp层socket
     */
    private Socket rawSocket;
    private Handshake handshake;
    private Protocol protocol;

    public RealConnection(Route route) {
        this.route = route;
    }

    /** 完成三次握手  **/
    public void connect(int connectTimeout, int readTimeout, int writeTimeout,
                        List<ConnectionSpec> connectionSpecs, boolean connectionRetryEnabled) {
        // protocol不为空，说明这个connection已经connect过了，protocol在while循环中赋值
        if (protocol != null) throw new IllegalStateException("already connected");

        // 线路的选择
        RouteException routeException = null;
        ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);

        if (route.address().sslSocketFactory() == null) {   // CLEARTEXT 明文传输
            if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
                throw new RouteException(new UnknownServiceException(
                        "CLEARTEXT communication not enabled for client"));
            }
            String host = route.address().url().host();
            if (!Platform.get().isCleartextTrafficPermitted(host)) {
                throw new RouteException(new UnknownServiceException(
                        "CLEARTEXT communication to " + host + " not permitted by network security policy"));
            }
        }

        // 连接开始
        while (protocol == null) {
            try {
                if (route.requiresTunnel()) { // 如果要求隧道模式，建立隧道连接，通常不是这种
                    buildTunneledConnection(connectTimeout, readTimeout, writeTimeout,
                            connectionSpecSelector);
                } else {  // 一般都走这条逻辑，建立socket连接
                    buildConnection(connectTimeout, readTimeout, writeTimeout, connectionSpecSelector);
                }
            } catch (IOException e) {
                closeQuietly(socket);
                closeQuietly(rawSocket);
                socket = null;
                rawSocket = null;
                source = null;
                sink = null;
                handshake = null;
                protocol = null;

                if (routeException == null) {
                    routeException = new RouteException(e);
                } else {
                    routeException.addConnectException(e);
                }

                if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
                    throw routeException;
                }
            }
        }
    }

    /**
     * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
     * proxy server can issue an auth challenge and then close the connection.
     * 做构建一个使用代理的HTTPS链接的所有工作。这里的捕获可能是因为代理服务器遇到一个认证的问题，捕获到异常会关闭链接
     * 使用的情况：
     * 1.使用HTTP代理的HTTPS链接
     */
    private void buildTunneledConnection(int connectTimeout, int readTimeout, int writeTimeout,
                                         ConnectionSpecSelector connectionSpecSelector) throws IOException {
        Request tunnelRequest = createTunnelRequest();
        HttpUrl url = tunnelRequest.url();
        int attemptedConnections = 0;
        int maxAttempts = 21;
        while (true) {
            if (++attemptedConnections > maxAttempts) {
                throw new ProtocolException("Too many tunnel connections attempted: " + maxAttempts);
            }

            connectSocket(connectTimeout, readTimeout);
            tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

            if (tunnelRequest == null) break; // Tunnel successfully created. 隧道成功创建，退出循环

            // The proxy decided to close the connection after an auth challenge. We need to create a new
            // connection, but this time with the auth credentials. 代理服务器产生身份怀疑觉得关闭当前链接，我们需要创建一个新的带身份证书的链接
            closeQuietly(rawSocket);
            rawSocket = null;
            sink = null;
            source = null;
        }

        establishProtocol(readTimeout, writeTimeout, connectionSpecSelector);
    }

    /**
     * Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket.
     * 完成构建一个完整的建立在raw socket基础上的HTTP和HTTPS连接的全部工作
     * 三种使用raw socket的情况
     * 1.无代理
     * 2.明文的HTTP代理（HTTP代理的非HTTPS链接）
     * 3.SOCKS代理
     */
    private void buildConnection(int connectTimeout, int readTimeout, int writeTimeout,
                                 ConnectionSpecSelector connectionSpecSelector) throws IOException {
        connectSocket(connectTimeout, readTimeout);
        establishProtocol(readTimeout, writeTimeout, connectionSpecSelector);
    }

    private void connectSocket(int connectTimeout, int readTimeout) throws IOException {
        Proxy proxy = route.proxy();
        Address address = route.address();

        // 根据代理类型，选择socket的类型，无代理或者HTTP代理使用SocketFactory的createSocket()，其他情况
        // （SOCKS代理）new出一个socket对象，把proxy作为参数
        rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
                ? address.socketFactory().createSocket()
                : new Socket(proxy);

        rawSocket.setSoTimeout(readTimeout);    // socket option time out
        try {
            // 完成特定于平台的连接建立，或判断运行的平台
            Platform.get().connectSocket(rawSocket, route.socketAddress(), connectTimeout);
        } catch (ConnectException e) {
            ConnectException ce = new ConnectException("Failed to connect to " + route.socketAddress());
            ce.initCause(e);
            throw ce;
        }
        source = Okio.buffer(Okio.source(rawSocket));
        sink = Okio.buffer(Okio.sink(rawSocket));
    }

    private void establishProtocol(int readTimeout, int writeTimeout,
                                   ConnectionSpecSelector connectionSpecSelector) throws IOException {
        if (route.address().sslSocketFactory() != null) {
            connectTls(readTimeout, writeTimeout, connectionSpecSelector);
        } else {
            protocol = Protocol.HTTP_1_1;
            socket = rawSocket;
        }

        if (protocol == Protocol.HTTP_2) {
            socket.setSoTimeout(0); // Framed connection timeouts are set per-stream.

            Http2Connection http2Connection = new Http2Connection.Builder(true)
                    .socket(socket, route.address().url().host(), source, sink)
                    .listener(this)
                    .build();
            http2Connection.start();

            // Only assign the framed connection once the preface has been sent successfully.
            this.allocationLimit = http2Connection.maxConcurrentStreams();
            this.http2Connection = http2Connection;
        } else {
            this.allocationLimit = 1;
        }
    }

    private void connectTls(int readTimeout, int writeTimeout,
                            ConnectionSpecSelector connectionSpecSelector) throws IOException {
        Address address = route.address();
        SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
        boolean success = false;
        SSLSocket sslSocket = null;
        try {
            // Create the wrapper over the connected socket.
            sslSocket = (SSLSocket) sslSocketFactory.createSocket(
                    rawSocket, address.url().host(), address.url().port(), true /* autoClose */);

            // Configure the socket's ciphers, TLS versions, and extensions.
            ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
            if (connectionSpec.supportsTlsExtensions()) {
                Platform.get().configureTlsExtensions(
                        sslSocket, address.url().host(), address.protocols());
            }

            // Force handshake. This can throw!
            sslSocket.startHandshake();
            Handshake unverifiedHandshake = Handshake.get(sslSocket.getSession());

            // Verify that the socket's certificates are acceptable for the target host.
            if (!address.hostnameVerifier().verify(address.url().host(), sslSocket.getSession())) {
                X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
                throw new SSLPeerUnverifiedException("Hostname " + address.url().host() + " not verified:"
                        + "\n    certificate: " + CertificatePinner.pin(cert)
                        + "\n    DN: " + cert.getSubjectDN().getName()
                        + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
            }

            // Check that the certificate pinner is satisfied by the certificates presented.
            address.certificatePinner().check(address.url().host(),
                    unverifiedHandshake.peerCertificates());

            // Success! Save the handshake and the ALPN protocol.
            String maybeProtocol = connectionSpec.supportsTlsExtensions()
                    ? Platform.get().getSelectedProtocol(sslSocket)
                    : null;
            socket = sslSocket;
            source = Okio.buffer(Okio.source(socket));
            sink = Okio.buffer(Okio.sink(socket));
            handshake = unverifiedHandshake;
            protocol = maybeProtocol != null
                    ? Protocol.get(maybeProtocol)
                    : Protocol.HTTP_1_1;
            success = true;
        } catch (AssertionError e) {
            if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
            throw e;
        } finally {
            if (sslSocket != null) {
                Platform.get().afterHandshake(sslSocket);
            }
            if (!success) {
                closeQuietly(sslSocket);
            }
        }
    }

    /**
     * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
     * the proxy connection. This may need to be retried if the proxy requires authorization.
     * 在HTTP代理基础上建立一个HTTPS链接，发送未加密的连接请求来创建代理链接。如果代理服务器需要认证可能会retry。
     */
    private Request createTunnel(int readTimeout, int writeTimeout, Request tunnelRequest,
                                 HttpUrl url) throws IOException {
        // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
        String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
        while (true) {
            Http1Codec tunnelConnection = new Http1Codec(null, null, source, sink);
            source.timeout().timeout(readTimeout, MILLISECONDS);
            sink.timeout().timeout(writeTimeout, MILLISECONDS);
            tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
            tunnelConnection.finishRequest();
            Response response = tunnelConnection.readResponse().request(tunnelRequest).build();
            // The response body from a CONNECT should be empty, but if it is not then we should consume
            // it before proceeding.
            long contentLength = HttpHeaders.contentLength(response);
            if (contentLength == -1L) {
                contentLength = 0L;
            }
            Source body = tunnelConnection.newFixedLengthSource(contentLength);
            Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            body.close();

            switch (response.code()) {
                case HTTP_OK:
                    // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
                    // that happens, then we will have buffered bytes that are needed by the SSLSocket!
                    // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
                    // that it will almost certainly fail because the proxy has sent unexpected data.
                    if (!source.buffer().exhausted() || !sink.buffer().exhausted()) {
                        throw new IOException("TLS tunnel buffered too many bytes!");
                    }
                    return null;

                case HTTP_PROXY_AUTH:
                    tunnelRequest = route.address().proxyAuthenticator().authenticate(route, response);
                    if (tunnelRequest == null)
                        throw new IOException("Failed to authenticate with proxy");

                    if ("close".equalsIgnoreCase(response.header("Connection"))) {
                        return tunnelRequest;
                    }
                    break;

                default:
                    throw new IOException(
                            "Unexpected response code for CONNECT: " + response.code());
            }
        }
    }

    /**
     * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
     * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
     * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
     * 创建使用http代理的TLS隧道Request对象。这个Request上的数据被明文发送到代理服务器上，所以隧道只包含最小的请求头。
     * 这样做避免了可能会发送敏感的明文数据（http cookies）到代理服务器的情况
     */
    private Request createTunnelRequest() {
        return new Request.Builder()
                .url(route.address().url())
                .header("Host", Util.hostHeader(route.address().url(), true))
                .header("Proxy-Connection", "Keep-Alive")
                .header("User-Agent", Version.userAgent()) // For HTTP/1.0 proxies like Squid.
                .build();
    }

    @Override
    public Route route() {
        return route;
    }

    public void cancel() {
        // Close the raw socket so we don't end up doing synchronous I/O.
        closeQuietly(rawSocket);
    }

    @Override
    public Socket socket() {
        return socket;
    }

    /**
     * Returns true if this connection is ready to host new streams.
     */
    public boolean isHealthy(boolean doExtensiveChecks) {
        if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
            return false;
        }

        if (http2Connection != null) {
            return !http2Connection.isShutdown();
        }

        if (doExtensiveChecks) {
            try {
                int readTimeout = socket.getSoTimeout();
                try {
                    socket.setSoTimeout(1);
                    if (source.exhausted()) {
                        return false; // Stream is exhausted; socket is closed.
                    }
                    return true;
                } finally {
                    socket.setSoTimeout(readTimeout);
                }
            } catch (SocketTimeoutException ignored) {
                // Read timed out; socket is good.
            } catch (IOException e) {
                return false; // Couldn't read; socket is closed.
            }
        }

        return true;
    }

    /**
     * Refuse incoming streams.
     */
    @Override
    public void onStream(Http2Stream stream) throws IOException {
        stream.close(ErrorCode.REFUSED_STREAM);
    }

    /**
     * When settings are received, adjust the allocation limit.
     */
    @Override
    public void onSettings(Http2Connection connection) {
        allocationLimit = connection.maxConcurrentStreams();
    }

    @Override
    public Handshake handshake() {
        return handshake;
    }

    /**
     * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
     * requests simultaneously.
     */
    public boolean isMultiplexed() {
        return http2Connection != null;
    }

    @Override
    public Protocol protocol() {
        if (http2Connection == null) {
            return protocol != null ? protocol : Protocol.HTTP_1_1;
        } else {
            return Protocol.HTTP_2;
        }
    }

    @Override
    public String toString() {
        return "Connection{"
                + route.address().url().host() + ":" + route.address().url().port()
                + ", proxy="
                + route.proxy()
                + " hostAddress="
                + route.socketAddress()
                + " cipherSuite="
                + (handshake != null ? handshake.cipherSuite() : "none")
                + " protocol="
                + protocol
                + '}';
    }
}
