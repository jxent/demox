package com.example.administrator.demox;

import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;


import org.xutils.view.annotation.ContentView;
import org.xutils.view.annotation.ViewInject;
import org.xutils.x;

@ContentView(R.layout.activity_main)
public class MainActivity extends AppCompatActivity {

    @ViewInject(R.id.test_1)
    private TextView test1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        x.view().inject(this);

        init();

//        LeakThread leakThread = new LeakThread();
//        leakThread.start();

    }

    class LeakThread extends Thread {
        @Override
        public void run() {
            try {
                Thread.sleep(6 * 60 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    private void init() {
        FragmentManager fm = getSupportFragmentManager();
        TestFragment f = new TestFragment();
        fm.beginTransaction()
                .replace(R.id.fragment_holder, f)
                .commit();
    }
}
