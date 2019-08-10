package com.example.administrator.demox;

import org.junit.Test;

import java.io.IOException;
import java.util.Random;

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
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("http://httpbin.org/get?index=1")
                .get()
                .build();
        Call call = client.newCall(request);

        Response resp = call.execute();
        ResponseBody body = resp.body();

        System.out.println(body.string());
    }

    @Test
    public void testPost() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
//                .readTimeout(1000*15, TimeUnit.MICROSECONDS)
                .build();
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(mediaType, "{\"name\":\"jason\"}");
        Request request = new Request.Builder()
                .url("https://httpbin.org/post")
                .post(requestBody)
                .build();
        Call call = client.newCall(request);
        Response response = call.execute();
        System.out.println(response.body().string());
    }

    @Test
    public void jiba() {

        breakAll:
        while (true) {
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
