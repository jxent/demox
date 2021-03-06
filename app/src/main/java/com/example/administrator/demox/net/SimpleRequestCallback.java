package com.example.administrator.demox.net;

import android.content.Context;
import android.widget.Toast;

/**
 * Created by Jason on 2018/1/12.
 */

public class SimpleRequestCallback implements IRequestCallback {

    private Context mContext;

    public SimpleRequestCallback(Context context) {
        mContext = context;
    }

    @Override
    public void onNetError(Throwable th) {
        Toast.makeText(mContext, "net error: " + th.getMessage(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSucceed(String result) {

    }

    @Override
    public void onFailure(Throwable throwable) {

    }
}
