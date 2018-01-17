package com.example.administrator.demox.net;

/**
 * Created by Jason on 2018/1/11.
 */

public interface IRequestCallback {

    void onNetError();

    void onSucceed(String result);

    void onFailure(Throwable throwable);
}
