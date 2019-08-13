/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;

import okhttp3.Connection;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.connection.StreamAllocation;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 */
public final class RealInterceptorChain implements Interceptor.Chain {
    private final List<Interceptor> interceptors;
    private final StreamAllocation streamAllocation;
    private final HttpCodec httpCodec;
    private final Connection connection;
    private final int index;
    private final Request request;
    /**
     * 分别记录每个对象proceed方法被执行的次数
     */
    private int calls;

    public RealInterceptorChain(List<Interceptor> interceptors, StreamAllocation streamAllocation,
                                HttpCodec httpCodec, Connection connection, int index, Request request) {
        this.interceptors = interceptors;
        this.connection = connection;
        this.streamAllocation = streamAllocation;
        this.httpCodec = httpCodec;
        this.index = index;
        this.request = request;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    public StreamAllocation streamAllocation() {
        return streamAllocation;
    }

    public HttpCodec httpStream() {
        return httpCodec;
    }

    @Override
    public Request request() {
        return request;
    }

    @Override
    public Response proceed(Request request) throws IOException {
        return proceed(request, streamAllocation, httpCodec, connection);
    }

    public Response proceed(Request request, StreamAllocation streamAllocation, HttpCodec httpCodec,
                            Connection connection) throws IOException {
        // index代表拦截器的索引，不应该超过拦截器集合的大小
        if (index >= interceptors.size()) {
            throw new AssertionError();
        }

        // 计数
        calls++;

        // If we already have a stream, confirm that the incoming request will use it.
        // 如果我们有了流对象，请确保即将到来的请求可以共用它
        // *** 在ConnectInterceptor中创建了httpCodec对象，从其中调用chain.proceed方法后到此httpCodec不为空 ***
        if (this.httpCodec != null && !sameConnection(request.url())) {
            throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
                    + " must retain the same host and port");
        }

        // If we already have a stream, confirm that this is the only call to chain.proceed().
        // 如果我们有流对象，请确保这是唯一的chain.proceed()的调用（calls不能大于1，否则状态异常）
        // *** 在ConnectInterceptor中创建了httpCodec对象，从其中调用chain.proceed方法后到此httpCodec不为空 ***
        if (this.httpCodec != null && calls > 1) {
            throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
                    + " must call proceed() exactly once");
        }

        /**
         * 此处会new出一个新的Chain对象，并将最初设置的拦截器集合对象、streamAllocation对象、
         * httpCodec对象以及connection对象继续向下传入，然后将index+1传入，最后将最初的request对象传入
         *
         * 取出index处的拦截器对象，执行其intercept方法，上面new出的chain对象作为参数传入。在intercept方法执行期间
         * 会执行入参chain的proceed方法，方法会类似递归的方式执行，直到CallServerInterceptor方法中不再执行proceed，
         * 整个过程完成，并将Response对象一层一层返回。
         */

        // 调用拦截器链中的下一个拦截器
        RealInterceptorChain next = new RealInterceptorChain(
                interceptors, streamAllocation, httpCodec, connection, index + 1, request);
        Interceptor interceptor = interceptors.get(index);
        // 最终返回response对象
        Response response = interceptor.intercept(next);

        // Confirm that the next interceptor made its required call to chain.proceed().
        /**
         * ConnectInterceptor中创建了httpCodec对象，此处判断为了确保之后我们自定义添加的多个network interceptor
         * 中的intercept方法必须调用了chain的proceed方法，并且每个方法必须只能调用一次
         */
        if (httpCodec != null && index + 1 < interceptors.size() && next.calls != 1) {
            throw new IllegalStateException("network interceptor " + interceptor
                    + " must call proceed() exactly once");
        }

        // Confirm that the intercepted response isn't null.
        // response不能是null，否则抛出在哪个interceptor中返回了null的空指针异常
        if (response == null) {
            throw new NullPointerException("interceptor " + interceptor + " returned null");
        }

        return response;
    }

    /**
     * 入参url是否和chain中持有的connection是相同的
     * 满足的条件： host相同且port相同
     *
     * @param url
     * @return
     */
    private boolean sameConnection(HttpUrl url) {
        return url.host().equals(connection.route().address().url().host())
                && url.port() == connection.route().address().url().port();
    }
}
