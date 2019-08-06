/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import okhttp3.Address;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http1.Http1Codec;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Codec;
import okhttp3.internal.http2.StreamResetException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * This class coordinates the relationship between three entities:
 * 协调Connections、Streams和Calls三者的关系
 * <p>
 * <ul>
 * <li><strong>Connections:</strong> physical socket connections to remote servers. These are
 * potentially slow to establish so it is necessary to be able to cancel a connection
 * currently being connected.
 * <li><strong>Streams:</strong> logical HTTP request/response pairs that are layered on
 * connections. Each connection has its own allocation limit, which defines how many
 * concurrent streams that connection can carry. HTTP/1.x connections can carry 1 stream
 * at a time, HTTP/2 typically carry multiple.
 * <li><strong>Calls:</strong> a logical sequence of streams, typically an initial request and
 * its follow up requests. We prefer to keep all streams of a single call on the same
 * connection for better behavior and locality.
 * </ul>
 * <p>
 * <p>Instances of this class act on behalf of the call, using one or more streams over one or more
 * connections. This class has APIs to release each of the above resources:
 * <p>
 * <ul>
 * <li>{@link #noNewStreams()} prevents the connection from being used for new streams in the
 * future. Use this after a {@code Connection: close} header, or when the connection may be
 * inconsistent.
 * <li>{@link #streamFinished streamFinished()} releases the active stream from this allocation.
 * Note that only one stream may be active at a given time, so it is necessary to call
 * {@link #streamFinished streamFinished()} before creating a subsequent stream with {@link
 * #newStream newStream()}.
 * <li>{@link #release()} removes the call's hold on the connection. Note that this won't
 * immediately free the connection if there is a stream still lingering. That happens when a
 * call is complete but its response body has yet to be fully consumed.
 * </ul>
 * <p>
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have the
 * smallest blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream
 * but not the other streams sharing its connection. But if the TLS handshake is still in progress
 * then canceling may break the entire connection.
 */
public final class StreamAllocation {
    public final Address address;
    private final ConnectionPool connectionPool;
    private final Object callStackTrace;
    // State guarded by connectionPool.
    private final RouteSelector routeSelector;
    private Route route;
    private int refusedStreamCount;
    private RealConnection connection;
    private boolean released;
    private boolean canceled;
    private HttpCodec codec;

    public StreamAllocation(ConnectionPool connectionPool, Address address, Object callStackTrace) {
        this.connectionPool = connectionPool;
        this.address = address;
        this.routeSelector = new RouteSelector(address, routeDatabase());
        this.callStackTrace = callStackTrace;
    }

    public HttpCodec newStream(OkHttpClient client, boolean doExtensiveHealthChecks) {
        int connectTimeout = client.connectTimeoutMillis();
        int readTimeout = client.readTimeoutMillis();
        int writeTimeout = client.writeTimeoutMillis();
        boolean connectionRetryEnabled = client.retryOnConnectionFailure();

        try {
            // 寻找并返回一个健康的RealConnection对象
            RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
                    writeTimeout, connectionRetryEnabled, doExtensiveHealthChecks);

            HttpCodec resultCodec;
            if (resultConnection.http2Connection != null) {
                resultCodec = new Http2Codec(client, this, resultConnection.http2Connection);
            } else {
                resultConnection.socket().setSoTimeout(readTimeout);
                resultConnection.source.timeout().timeout(readTimeout, MILLISECONDS);
                resultConnection.sink.timeout().timeout(writeTimeout, MILLISECONDS);
                resultCodec = new Http1Codec(
                        client, this, resultConnection.source, resultConnection.sink);
            }

            synchronized (connectionPool) {
                codec = resultCodec;
                return resultCodec;
            }
        } catch (IOException e) {
            throw new RouteException(e);
        }
    }

    /**
     * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
     * until a healthy connection is found.
     * 循环找到一个合格的健康的链接
     */
    private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
                                                 int writeTimeout, boolean connectionRetryEnabled,
                                                 boolean doExtensiveHealthChecks)
            throws IOException {
        while (true) {
            RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
                    connectionRetryEnabled);

            // If this is a brand new connection, we can skip the extensive health checks.如果这是个名牌（O(∩_∩)O~）的新connection，那么我们略过大量的健康检查
            synchronized (connectionPool) {
                if (candidate.successCount == 0) {
                    return candidate;
                }
            }

            // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
            // isn't, take it out of the pool and start again.
            // （潜在的延迟的可能）再次检查确认这个connection是否合格。如果不是从池中删掉然后继续。。。
            if (!candidate.isHealthy(doExtensiveHealthChecks)) {
                noNewStreams();
                continue;
            }

            return candidate;
        }
    }

    /**
     * Returns a connection to host a new stream. This prefers the existing connection if it exists,
     * then the pool, finally building a new connection.
     * 返回一个connection（实际的流）关联一个新的stream（逻辑上的流），
     * 1.优先返回已经存在的（刚刚从池中匹配或者刚刚new出来的）
     * 2.没有，再去链接池里匹配
     * 3.最后都没有，再new一个新的
     */
    private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
                                          boolean connectionRetryEnabled) throws IOException {
        Route selectedRoute;
        synchronized (connectionPool) {
            if (released) throw new IllegalStateException("released");
            if (codec != null) throw new IllegalStateException("codec != null");
            if (canceled) throw new IOException("Canceled");

            // 1. 优先考虑最近从连接池中返回的对象或新new的对象（在181行和201行）
            RealConnection allocatedConnection = this.connection;
            if (allocatedConnection != null && !allocatedConnection.noNewStreams) {
                return allocatedConnection;
            }

            // 2. 尝试从链接池中返回一个
            RealConnection pooledConnection = Internal.instance.get(connectionPool, address, this);
            if (pooledConnection != null) {
                this.connection = pooledConnection;
                return pooledConnection;
            }

            selectedRoute = route;
        }

        if (selectedRoute == null) {
            selectedRoute = routeSelector.next();       // 递归选择可用的路由
            synchronized (connectionPool) {
                route = selectedRoute;
                refusedStreamCount = 0;
            }
        }
        // 1、2的操作都未成功，new一个新的RealConnection对象返回
        RealConnection newConnection = new RealConnection(selectedRoute);

        synchronized (connectionPool) {
            acquire(newConnection);     // 把这个类对象添加入刚new出来的newConnection的allocations中
            Internal.instance.put(connectionPool, newConnection);   // 把newConnection放到链接池
            this.connection = newConnection;    // 赋值给类变量
            if (canceled) throw new IOException("Canceled");
        }

        // 连接并握手
        newConnection.connect(connectTimeout, readTimeout, writeTimeout, address.connectionSpecs(),
                connectionRetryEnabled);
        routeDatabase().connected(newConnection.route());   // 更新本地数据库

        return newConnection;
    }

    public void streamFinished(boolean noNewStreams, HttpCodec codec) {
        synchronized (connectionPool) {
            if (codec == null || codec != this.codec) {
                throw new IllegalStateException("expected " + this.codec + " but was " + codec);
            }
            if (!noNewStreams) {
                connection.successCount++;
            }
        }
        deallocate(noNewStreams, false, true);
    }

    public HttpCodec codec() {
        synchronized (connectionPool) {
            return codec;
        }
    }

    private RouteDatabase routeDatabase() {
        return Internal.instance.routeDatabase(connectionPool);
    }

    public synchronized RealConnection connection() {
        return connection;
    }

    public void release() {
        deallocate(false, true, false);
    }

    /**
     * Forbid new streams from being created on the connection that hosts this allocation.
     * 禁止持有这个allocation的connection创建新的streams对象
     */
    public void noNewStreams() {
        deallocate(true, false, false);
    }

    /**
     * Releases resources held by this allocation. If sufficient resources are allocated, the
     * connection will be detached or closed.
     * 释放此allocation所持有的资源。如果释放出足够多的资源，这个连接就会被卸载或者关闭
     * todo “卸载或关闭”不理解
     */
    private void deallocate(boolean noNewStreams, boolean released, boolean streamFinished) {
        RealConnection connectionToClose = null;
        synchronized (connectionPool) {
            if (streamFinished) {
                this.codec = null;
            }
            if (released) {
                this.released = true;
            }
            if (connection != null) {
                if (noNewStreams) {
                    connection.noNewStreams = true;
                }
                if (this.codec == null && (this.released || connection.noNewStreams)) {
                    release(connection);
                    // fixme > 移除完this之后，判断一下这个connection所关联的StreamAllocation列表是否为空，
                    // fixme > 如果已经为空，则表明这个connection已经无事可做了，idle了，可以从connection
                    // fixme > pool中移除了
                    if (connection.allocations.isEmpty()) {
                        connection.idleAtNanos = System.nanoTime(); // 标记链接空闲的时刻
                        // 这个方法会唤醒正在wait中的connection poll的cleanup线程，并执行clean up
                        if (Internal.instance.connectionBecameIdle(connectionPool, connection)) {
                            connectionToClose = connection;
                        }
                    }
                    connection = null;
                }
            }
        }
        if (connectionToClose != null) {
            Util.closeQuietly(connectionToClose.socket());
        }
    }

    public void cancel() {
        HttpCodec codecToCancel;
        RealConnection connectionToCancel;
        synchronized (connectionPool) {
            canceled = true;
            codecToCancel = codec;
            connectionToCancel = connection;
        }
        if (codecToCancel != null) {
            codecToCancel.cancel();
        } else if (connectionToCancel != null) {
            connectionToCancel.cancel();
        }
    }

    public void streamFailed(IOException e) {
        boolean noNewStreams = false;

        synchronized (connectionPool) {
            if (e instanceof StreamResetException) {
                StreamResetException streamResetException = (StreamResetException) e;
                if (streamResetException.errorCode == ErrorCode.REFUSED_STREAM) {
                    refusedStreamCount++;
                }
                // On HTTP/2 stream errors, retry REFUSED_STREAM errors once on the same connection. All
                // other errors must be retried on a new connection.
                if (streamResetException.errorCode != ErrorCode.REFUSED_STREAM || refusedStreamCount > 1) {
                    noNewStreams = true;
                    route = null;
                }
            } else if (connection != null && !connection.isMultiplexed()
                    || e instanceof ConnectionShutdownException) {
                noNewStreams = true;

                // If this route hasn't completed a call, avoid it for new connections.
                if (connection.successCount == 0) {
                    if (route != null && e != null) {
                        routeSelector.connectFailed(route, e);
                    }
                    route = null;
                }
            }
        }

        deallocate(noNewStreams, false, true);
    }

    /**
     * Use this allocation to hold {@code connection}. Each call to this must be paired with a call to
     * {@link #release} on the same connection.
     * 使用这个allocation去hold这个connection。
     * fixme ： 每一个acquire这个allocation的call 必须有配对的一个call
     * release了这个allocation在同一个connection上。
     */
    public void acquire(RealConnection connection) {
        assert (Thread.holdsLock(connectionPool));
        connection.allocations.add(new StreamAllocationReference(this, callStackTrace));
    }

    /**
     * Remove this allocation from the connection's list of allocations.
     * 从connection的stream allocation列表中移除 this
     */
    private void release(RealConnection connection) {
        for (int i = 0, size = connection.allocations.size(); i < size; i++) {
            Reference<StreamAllocation> reference = connection.allocations.get(i);
            if (reference.get() == this) {
                connection.allocations.remove(i);
                return;
            }
        }
        throw new IllegalStateException();
    }

    public boolean hasMoreRoutes() {
        return route != null || routeSelector.hasNext();
    }

    @Override
    public String toString() {
        return address.toString();
    }

    public static final class StreamAllocationReference extends WeakReference<StreamAllocation> {
        /**
         * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
         * identifying the origin of connection leaks.
         */
        public final Object callStackTrace;

        StreamAllocationReference(StreamAllocation referent, Object callStackTrace) {
            super(referent);
            this.callStackTrace = callStackTrace;
        }
    }
}
