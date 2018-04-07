/*
 * Copyright (C) 2012 The Android Open Source Project
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

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Sink;

/**
 * Encodes HTTP requests and decodes HTTP responses.
 * 编码HTTP请求以及解码HTTP响应
 */
public interface HttpCodec {
    /**
     * The timeout to use while discarding a stream of input data. Since this is used for connection
     * reuse, this timeout should be significantly less than the time it takes to establish a new
     * connection.
     */
    int DISCARD_STREAM_TIMEOUT_MILLIS = 100;

    /**
     * Returns an output stream where the request body can be streamed.
     * 返回一个request body的输出流
     */
    Sink createRequestBody(Request request, long contentLength);

    /**
     * This should update the HTTP engine's sentRequestMillis field.
     */
    void writeRequestHeaders(Request request) throws IOException;

    /**
     * Flush the request to the underlying socket.把请求全部flush到底层的socket中
     */
    void finishRequest() throws IOException;

    /**
     * Read and return response headers.读取并返回相应的header
     */
    Response.Builder readResponseHeaders() throws IOException;

    /**
     * Returns a stream that reads the response body.返回相应的body
     */
    ResponseBody openResponseBody(Response response) throws IOException;

    /**
     * Cancel this stream. Resources held by this stream will be cleaned up, though not synchronously.
     * That may happen later by the connection pool thread.
     * 取消这个流，流所拥有的资源将被清理，尽管不是同步的。这个操作之后会在链接池线程里发生
     */
    void cancel();
}
