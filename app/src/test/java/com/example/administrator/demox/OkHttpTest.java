package com.example.administrator.demox;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpTest {

    @Test
    public void testGet() throws IOException {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        OkHttpClient client = builder
                .cache(new Cache(new File("cache"), 24 * 1024 * 1024))
                .build();
        Request request = new Request.Builder()
                .url("https://httpbin.org/image")
                .get()
                .build();
        System.out.println("--------->>> 请求 <<<------------");
        System.out.println(request.toString());
        System.out.println("\n\n");
        Call call = client.newCall(request);

        Response resp = call.execute();
        ResponseBody body = resp.body();

        System.out.println("--------->>> 响应header <<<------------");
        System.out.println(resp.headers().toString());
        System.out.println("------------>>> 响应body <<<------------");
        System.out.println(body.string());
    }

    @Test
    public void testPost() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(1000 * 10, TimeUnit.MILLISECONDS)
                .build();
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(mediaType, "{\"name\":\"jason\"}");
        Request request = new Request.Builder()
                .url("https://httpbin.org/status/404")
                .post(requestBody)
                .build();
        Call call = client.newCall(request);
        Response response = call.execute();
        System.out.println(response.body().string());
    }

    @Test
    public void testBreakLabel() {

        breakAll:
//        while(true){
        for (; ; ) {
            Random random = new Random();
            int ret = random.nextInt(100);
            System.out.println("随机：" + ret);
            switch (ret % 5) {
                case 0:
                    System.out.println("偶数：" + ret);
                    break;
                case 1:
                    System.out.println("终结者: " + ret + " 中断所有");
                    break breakAll;
                default:
                    break;
            }
        }
    }
}