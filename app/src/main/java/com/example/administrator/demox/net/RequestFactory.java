package com.example.administrator.demox.net;

/**
 * 网络请求工厂，配置请求使用的库（okhttp，httpurlconnection）
 * Created by Jason on 2018/1/11.
 */

public class RequestFactory {

    public static IRequestManager getRequestManager(){
        return OkHttpRequestManager.getInstance();
    }
}
