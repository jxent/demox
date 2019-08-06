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
package okhttp3;

import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same {@link Address} may share a {@link Connection}. This class implements the policy
 * of which connections to keep open for future use.
 * 管理http和http/2的链接，以便减少网络请求延迟。同一个address将共享同一个connection。该类实现了复用连接的目标。
 */
public final class ConnectionPool {
    /**
     * Background threads are used to cleanup expired connections. There will be at most a single
     * thread running per connection pool. The thread pool executor permits the pool itself to be
     * garbage collected.一个用于清除过期链接的线程池，每个线程池最多只能运行一个线程，并且这个线程池允许被垃圾回收
     */
    private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
            Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

    final RouteDatabase routeDatabase = new RouteDatabase();    // 路由的数据库，用来记录不可用的route，代码中并未使用

    /**
     * maxIdleConnections：The maximum number of idle connections for each address.每个address的最大空闲连接数，OkHttp只是限制与同一个远程服务器的空闲连接数量，对整体的空闲连接并没有限制。
     * keepAliveDurationNs：保持活着的时间，否则清理将旋转循环
     */
    private final int maxIdleConnections;
    private final long keepAliveDurationNs;
    private final Deque<RealConnection> connections = new ArrayDeque<>();   // 链接的双向队列
    boolean cleanupRunning; // 清理任务正在执行的标志
    // 清理任务
    private final Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                long waitNanos = cleanup(System.nanoTime());
                if (waitNanos == -1) return;
                if (waitNanos > 0) {
                    long waitMillis = waitNanos / 1000000L;
                    waitNanos -= (waitMillis * 1000000L);
                    synchronized (ConnectionPool.this) {
                        try {
                            ConnectionPool.this.wait(waitMillis, (int) waitNanos);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    };

    /**
     * Create a new connection pool with tuning parameters appropriate for a single-user application.
     * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
     * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
     * 创建一个适用于单个应用程序的新连接池。该连接池的参数将在未来的okhttp中发生改变，目前最多可容乃5个空闲的连接，存活期是5分钟
     */
    public ConnectionPool() {
        this(5, 5, TimeUnit.MINUTES);
    }

    public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
        this.maxIdleConnections = maxIdleConnections;
        this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

        // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
        if (keepAliveDuration <= 0) {
            throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
        }
    }

    /**
     * 返回池中空闲connection的个数
     */
    public synchronized int idleConnectionCount() {
        int total = 0;
        for (RealConnection connection : connections) {
            if (connection.allocations.isEmpty()) total++;
        }
        return total;
    }

    /**
     * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
     * only idle connections and HTTP/2 connections. Since OkHttp 2.7 this includes all connections,
     * both active and inactive. Use {@link #idleConnectionCount()} to count connections not currently
     * in use.
     */
    public synchronized int connectionCount() {
        return connections.size();
    }

    /**
     * Returns a recycled connection to {@code address}, or null if no such connection exists.
     */
    RealConnection get(Address address, StreamAllocation streamAllocation) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            if (connection.allocations.size() < connection.allocationLimit
                    && address.equals(connection.route().address)
                    && !connection.noNewStreams) {
                streamAllocation.acquire(connection);
                return connection;
            }
        }
        return null;
    }

    /**
     * 放入连接池
     * @param connection 连接对象
     */
    void put(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (!cleanupRunning) {
            cleanupRunning = true;
            executor.execute(cleanupRunnable);
        }
        connections.add(connection);
    }

    /**
     * Notify this pool that {@code connection} has become idle. Returns true if the connection has
     * been removed from the pool and should be closed.
     */
    boolean connectionBecameIdle(RealConnection connection) {
        assert (Thread.holdsLock(this));        // 断言这段代码是否被加锁ConnectionPool执行，如果不是会报错！
        if (connection.noNewStreams || maxIdleConnections == 0) {
            connections.remove(connection);
            return true;
        } else {
            notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
            return false;
        }
    }

    /**
     * Close and remove all idle connections in the pool.关闭移除池中所有的空闲链接
     */
    public void evictAll() {
        List<RealConnection> evictedConnections = new ArrayList<>();
        synchronized (this) {
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();
                if (connection.allocations.isEmpty()) {
                    connection.noNewStreams = true;
                    evictedConnections.add(connection);
                    i.remove();
                }
            }
        }

        for (RealConnection connection : evictedConnections) {
            closeQuietly(connection.socket());
        }
    }

    /**
     * Performs maintenance on this pool, evicting the connection that has been idle the longest if
     * either it has exceeded the keep alive limit or the idle connections limit.
     * <p>
     * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
     * -1 if no further cleanups are required.
     */
    long cleanup(long now) {
        int inUseConnectionCount = 0;   // 使用中的链接的计数
        int idleConnectionCount = 0;    // 空闲链接的计数
        RealConnection longestIdleConnection = null;        // 最长空闲链接
        long longestIdleDurationNs = Long.MIN_VALUE;        // 最长空闲链接的空闲时长

        // Find either a connection to evict, or the time that the next eviction is due. 查找出一个可以被清理的链接并清理或者返回一个下次执行的间隔时长
        synchronized (this) {
            // 遍历队列中的连接对象，并标记不活跃、空闲的连接（泄露连接）
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();

                // If the connection is in use, keep searching.
                // 迭代connection的StreamAllocation集合，如果链接正在被使用，continue，并计数
                if (pruneAndGetAllocationCount(connection, now) > 0) {
                    inUseConnectionCount++;
                    continue;
                }

                idleConnectionCount++;

                // 为最长空闲链接和空闲时长赋值，找出空闲最长的链接
                long idleDurationNs = now - connection.idleAtNanos;
                if (idleDurationNs > longestIdleDurationNs) {
                    longestIdleDurationNs = idleDurationNs;
                    longestIdleConnection = connection;
                }
            }

            if (longestIdleDurationNs >= this.keepAliveDurationNs
                    || idleConnectionCount > this.maxIdleConnections) {
                // 找到一个可以被清理的链接。从列表中移除它，然后在下边的同步块以外关闭它。
                connections.remove(longestIdleConnection);
            } else if (idleConnectionCount > 0) {
                // 有空闲链接、返回到期可以被清理的时长
                return keepAliveDurationNs - longestIdleDurationNs;
            } else if (inUseConnectionCount > 0) {
                // 所有链接都在使用，返回一个keepAliveDurationNs
                return keepAliveDurationNs;
            } else {
                // 没有链接，退出清理，标记位置空
                cleanupRunning = false;
                return -1;
            }
        }

        closeQuietly(longestIdleConnection.socket());       // 关闭刚刚被清理的链接

        // 立即开始下一次清理
        return 0;
    }

    /**
     * Prunes any leaked allocations and then returns the number of remaining live allocations on
     * {@code connection}. Allocations are leaked if the connection is tracking them but the
     * application code has abandoned them. Leak detection is imprecise and relies on garbage
     * collection.
     * 删除泄露的分配并返回链接保持活着的分配的数量。链接依然在跟踪分配但是应用却抛弃了的分配被视为泄露的分配。
     * 泄露检查是不精确的并且依赖垃圾回收
     */
    private int pruneAndGetAllocationCount(RealConnection connection, long now) {
        List<Reference<StreamAllocation>> references = connection.allocations;
        for (int i = 0; i < references.size(); ) {
            Reference<StreamAllocation> reference = references.get(i);

            if (reference.get() != null) {
                i++;
                continue;
            }

            // We've discovered a leaked allocation. This is an application bug.
            // 应用bug可以导致链接被泄露
            StreamAllocation.StreamAllocationReference streamAllocRef =
                    (StreamAllocation.StreamAllocationReference) reference;
            String message = "A connection to " + connection.route().address().url()
                    + " was leaked. Did you forget to close a response body?";
            Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);

            references.remove(i);
            connection.noNewStreams = true;

            // If this was the last allocation, the connection is eligible for immediate eviction.
            // 如果是最后一个allocation，则此链接就可以被立即清除了
            if (references.isEmpty()) {
                connection.idleAtNanos = now - keepAliveDurationNs; // 在哪个时间点开始idle的
                // 返回0，说明此连接已经没有被引用了
                return 0;
            }
        }
        // 否则返回引用数
        return references.size();
    }
}
