package com.example.administrator.demox;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.administrator.demox.net.RequestFactory;
import com.example.administrator.demox.net.SimpleRequestCallback;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.HttpParams;

import org.xutils.common.Callback;
import org.xutils.http.RequestParams;
import org.xutils.view.annotation.ContentView;
import org.xutils.view.annotation.Event;
import org.xutils.view.annotation.ViewInject;
import org.xutils.x;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.OkHttpClient;


/**
 * Created by Jason on 2017/12/20.
 */

@ContentView(R.layout.fragment_layout)
public class TestFragment extends Fragment {

    private static final String TEST_URL = "http://www.163.com/";
    final MyHandler mHandler = new MyHandler(this);
    @ViewInject(R.id.result)
    private TextView resultTV;
    @ViewInject(R.id.ok_1)
    private TextView ok1;
    @ViewInject(R.id.ok_2)
    private TextView ok2;
    @ViewInject(R.id.ok_3)
    private TextView ok3;
    @ViewInject(R.id.ok_4)
    private TextView ok4;
    @ViewInject(R.id.clear)
    private TextView clear;
    @ViewInject(R.id.size)
    private TextView size;
    private String info;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = x.view().inject(this, inflater, container);
        ok1.setText("OkHttp3.5.0");
        ok2.setText("HttpURLConnection");
        ok3.setText("XUtil3");
        ok4.setText("OkGo");

        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultTV.setText("");
            }
        });
        return root;
    }

    /**
     * 四个方法返回结果不相同
     * 第一个方法返回了pc端的数据，其他三个方法返回的是手机端的数据，可能原因是user_agent的设置
     **/


    @Event(R.id.ok_1)
    private void visitOkHttp(View view) {

        new Thread() {
            @Override
            public void run() {
                // RequestFactory封装了请求操作，内部使用OkHttp3.5，但是是在主线程工作的
                RequestFactory.getRequestManager().get(TEST_URL, new SimpleRequestCallback(getActivity()) {
                    @Override
                    public void onSucceed(String result) {
                        super.onSucceed(result);

                        Message msg = Message.obtain();
                        msg.obj = result;
                        mHandler.sendMessage(msg);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        super.onFailure(throwable);
                    }
                });

                /*Map<String, String> params = new HashMap<>();
                RequestFactory.getRequestManager().post(TEST_URL, params, new IRequestCallback() {
                    @Override
                    public void onNetError() {

                    }

                    @Override
                    public void onSucceed(String result) {
                        Message msg = Message.obtain();
                        msg.obj = result;
                        mHandler.sendMessage(msg);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {

                    }
                });*/
            }
        }.start();
    }


    @Event(R.id.ok_2)
    private void visitHttpURLConnection(View view) {

        new Thread() {
            @Override
            public void run() {
                super.run();

                try {
                    OkHttpClient okHttpClient = new OkHttpClient();
//                    URL.setURLStreamHandlerFactory(okHttpClient);

                    URL url = new URL(TEST_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
//                    connection.setRequestProperty("Connection", "close");
                    connection.setReadTimeout(15000);
                    connection.setConnectTimeout(15000);

                    // 在连接前调用getOutputStream可以获得要被写入的流对象
                    /*OutputStream outputStream = connection.getOutputStream();
                    byte[] op = new byte[1024 * 10];
                    outputStream.write(op);
                    String opStr = new String(op);
                    Log.e("jason", opStr);*/

                    connection.connect();   // ------> 发起请求

                    if (connection.getResponseCode() == 200) {      // --> 读取响应

                        InputStream in = connection.getInputStream();
                        StringBuffer out = new StringBuffer();

                        byte[] b = new byte[4096];
                        for (int n; (n = in.read(b)) != -1; ) {
                            out.append(new String(b, 0, n));
                        }

                        Message msg = Message.obtain();
                        msg.obj = out.toString();
                        mHandler.sendMessage(msg);
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }


    @Event(R.id.ok_3)
    private void visitByXUtils(View view) {
        Map<String, Object> map = new HashMap<>();
        RequestParams params = new RequestParams(TEST_URL);
        if (null != map) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                params.addBodyParameter(entry.getKey(), (String) entry.getValue());
            }
        }
        x.http().get(params, new Callback.CommonCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Message msg = Message.obtain();
                msg.obj = result;
                mHandler.sendMessage(msg);
            }

            @Override
            public void onError(Throwable ex, boolean isOnCallback) {

            }

            @Override
            public void onCancelled(CancelledException cex) {

            }

            @Override
            public void onFinished() {

            }
        });

        /*x.http().post(params, new Callback.CommonCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Message msg = Message.obtain();
                msg.obj = result;
                mHandler.sendMessage(msg);
            }

            @Override
            public void onError(Throwable ex, boolean isOnCallback) {

            }

            @Override
            public void onCancelled(CancelledException cex) {

            }

            @Override
            public void onFinished() {

            }
        });*/
    }


    @Event(R.id.ok_4)
    private void visitByOkGo(View view) {
        HttpParams params = new HttpParams();
//        params.put("sign", "");
        OkGo.<String>get(TEST_URL)
                .tag(this)
                .params(params)
                .execute(new StringCallback() {

                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        Message msg = Message.obtain();
                        msg.obj = response.body().toString();
                        Headers headers = response.headers();
                        for (int i = 0; i < headers.names().size(); i++) {
                            Log.e("jason", headers.name(i) + " ----------------- " + headers.get(headers.name(i)));
                        }

                        mHandler.sendMessage(msg);
                    }
                });
    }


    static class MyHandler extends Handler {

        WeakReference<Fragment> fragment;

        public MyHandler(Fragment fr) {
            fragment = new WeakReference<>(fr);
        }

        @Override
        public void handleMessage(Message msg) {
            String str = (String) msg.obj;
            ((TestFragment) fragment.get()).resultTV.setText(str);

//            ((TestFragment) fragment.get()).size.setText();
        }
    }

}
