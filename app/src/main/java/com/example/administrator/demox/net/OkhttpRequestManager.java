package com.example.administrator.demox.net;

import android.text.TextUtils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by Jason on 2018/1/11.
 */

public class OkhttpRequestManager implements IRequestManager {

    private static OkhttpRequestManager instance;
    private static Object object = new Object();

    private OkhttpRequestManager() {

    }

    public static OkhttpRequestManager getInstance() {
        if (instance == null) {
            synchronized (object) {
                if (instance == null) {
                    instance = new OkhttpRequestManager();
                }
            }
        }
        return instance;
    }


    @Override
    public void get(String url, IRequestCallback requestCallback) {

        /*
        // 网络连接不可用，网络错误
        if(isNetEnable){
            requestCallback.onNetError();
            return;
        }*/

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool())       // 自定义参数
//                .proxy(new Proxy(Proxy.Type.HTTP, null))  // 设置Http代理服务器
                .build();

        Request request = new Request.Builder()
                .url(url)
                .method("GET", null)
                .build();

        Call call = okHttpClient.newCall(request);

        try {

            Response response = call.execute();
            String result = response.body().string();
            if (!TextUtils.isEmpty(result)) {
                requestCallback.onSucceed(result);
            } else {
                requestCallback.onFailure(new NullPointerException("no result !!!"));
            }

        } catch (SocketTimeoutException e) {

            requestCallback.onNetError();   // 连接出现超时错误

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void post(String url, Map params, IRequestCallback requestCallback) {

    }

    @Override
    public void put(String url, IRequestCallback requestCallback) {

    }

    @Override
    public void delete(String url, IRequestCallback requestCallback) {

    }
}
