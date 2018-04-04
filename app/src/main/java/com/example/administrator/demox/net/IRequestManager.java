package com.example.administrator.demox.net;

import java.util.Map;

/**
 * Created by Jason on 2018/1/11.
 */

public interface IRequestManager {

    void get(String url, IRequestCallback requestCallback);

    void post(String url, Map param, IRequestCallback requestCallback);

    void put(String url, IRequestCallback requestCallback);

    void delete(String url, IRequestCallback requestCallback);

    void download(String url, IRequestCallback requestCallback);
}
