package com.example.administrator.demox.net;

/**
 * Created by Jason on 2018/1/11.
 */

public class RequestFactory {

    public static IRequestManager getRequestManager(){
        return OkhttpRequestManager.getInstance();
    }
}
