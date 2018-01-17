package com.example.administrator.demox;

import android.support.multidex.MultiDexApplication;


import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheEntity;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.HttpParams;

import org.xutils.x;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import okhttp3.OkHttpClient;


/**
 * Created by Jason on 2017/12/20.
 */

public class BaseApp extends MultiDexApplication {

//    private RefWatcher refWatcher;
    @Override
    public void onCreate() {
        super.onCreate();

        x.Ext.init(this);

        initOkGo();

//        refWatcher = setupLeakCanary();
    }

    /*private RefWatcher setupLeakCanary() {
        if (LeakCanary.isInAnalyzerProcess(this)) {
            return RefWatcher.DISABLED;
        }
        return LeakCanary.install(this);
    }

    public static RefWatcher getRefWatcher(Context context) {
        BaseApp app = (BaseApp) context.getApplicationContext();
        return app.refWatcher;
    }*/

    public void initOkGo(){
        //---------这里给出的是示例代码,告诉你可以这么传,实际使用的时候,根据需要传,不需要就不传-------------//
        HttpHeaders headers = new HttpHeaders();
//        headers.put("commonHeaderKey1", "commonHeaderValue1");    //header不支持中文，不允许有特殊字符
//        headers.put("commonHeaderKey2", "commonHeaderValue2");
        HttpParams params = new HttpParams();
//        params.put("commonParamsKey1", "commonParamsValue1");     //param支持中文,直接传,不要自己编码
//        params.put("commonParamsKey2", "这里支持中文参数");
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        //------------------------------------------------------------------------------------配置log
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkGo");
        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);        //log打印级别，决定了log显示的详细程度
        loggingInterceptor.setColorLevel(Level.INFO);                               //log颜色级别，决定了log在控制台显示的颜色
        builder.addInterceptor(loggingInterceptor);                                 //添加OkGo默认debug日志
        //---------------------------------------------------------------------------------全局的读取超时时间
        /**
         * 继续前面的，现在通道连接建立完成了，客户端也终于把数据发给服务端了，
         * 服务端巴拉巴拉一顿计算，把客户端需要的数据准备好了，准备返回给客户端。
         * but，要搞事情了，网络不通或者客户端出了毛病，客户端无法接受到服务端的数据了，
         * 类比之前的分析，客户端要这么傻傻的等着服务端发数据么，就算你等着他也发不过来了是不，
         * 这时候就有了个readTimeout时间来控制这个过程，告诉客户端收不到服务端的数据时，要傻傻等多久。
         */
        builder.readTimeout(OkGo.DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        //全局的写入超时时间
        /***
         * 基于前面的通道建立完成后，客户端终于可以向服务端发送数据了，客户端发送数据是不是要把数据写出去啊，
         * 所以叫写入时间，突然，服务器挂了，客户端能知道服务器挂了么，不知道的，所以客户端还在继续傻傻的向服务端写数据，
         * 可是服务端能收到这个数据么，肯定收不到，服务端都挂了，怎么收，同样的，客户端这个数据其实是写不出去的，
         * 客户端又写不出去，他又不知道服务端不能接受数据了，难道要一直这么等着服务端缓过来？肯定是不可能的哈，
         * 这样会造成资源的极端浪费，所以这个时候就有个writeTimeout时间控制这个傻傻的客户端要等服务端多长时间。
         */
        builder.writeTimeout(OkGo.DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        //全局的连接超时时间
        /**
         * 指http建立通道的时间，我们知道http底层是基于TCP/IP协议的，而TCP协议有个三次握手协议，所谓三次握手简单的理解为
         * 客户端问服务端：我要准备给你发数据了，你准备好了么
         * 服务端向客户端回答：我准备好了，你可以发数据了
         * 客户端回答服务端：我收到你的消息了，我要发数据了
         * 然后巴拉巴拉一堆数据过去了。 这里就能看出来，只有这三次握手建立后，才能开始发送数据，
         * 否则数据是无法发送的，那么建立这个通道的时间就叫做connectTimeout，想一想，如果我们网络不好，
         * 平均建立这个通道就要10秒，结果我们代码中设定的这个时间是5秒，那么这个连接永远建立不起来，
         * 建立到一半，就中断了
         */
        builder.connectTimeout(OkGo.DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        /**
         *现在这三个时间我们都有了印象，他是控制了http进行数据交互的三个阶段的超时时间，
         * 试想一下，假如我们把这三个时间都设置为一分钟，那么最坏最巧合的时候，
         * 刚好connectTimeout要超时候，啪，连上了，然后刚好writeTimeout要超时的时候，
         * 啪，数据发出去了，然后又刚好readTimeout要超时的时候，啪，数据收到了，
         * 所以你等了三分钟，依然没有超时，数据还能正常收到。懂了么？只是这种情况实在太难遇到！
         * */
        //----------------------------------------------------------------------------------cookie配置
        /**
         * 如果你用到了Cookie的持久化或者叫Session的保持，那么建议配置一个Cookie，
         * 这个也是可以自定义的，不一定非要用OkGo自己的，以下三个是OkGo默认提供的三种方式，
         * 可以选择添加，也可以自己实现CookieJar的接口，自己管理cookie。
         * */
        //使用sp保持cookie，如果cookie不过期，则一直有效
        //builder.cookieJar(new CookieJarImpl(new SPCookieStore(this)));
        //使用数据库保持cookie，如果cookie不过期，则一直有效
        //builder.cookieJar(new CookieJarImpl(new DBCookieStore(this)));
        //使用内存保持cookie，app退出后，cookie消失
        //builder.cookieJar(new CookieJarImpl(new MemoryCookieStore()));
        //---------------------------------------------------------------------------------Https配置，以下几种方案根据需要自己设置
        /**
         * 这个也是可以自定义的，HttpsUtils只是框架内部提供的方便管理Https的一个工具类，
         * 你也可以自己实现，最好只要给OkHttpClient.Builder传递一个sslSocketFactory就行。
         **/
        //方法一：信任所有证书,不安全有风险
        //HttpsUtils.SSLParams sslParams1 = HttpsUtils.getSslSocketFactory();
        //方法二：自定义信任规则，校验服务端证书
        //HttpsUtils.SSLParams sslParams2 = HttpsUtils.getSslSocketFactory(new SafeTrustManager());
        //方法三：使用预埋证书，校验服务端证书（自签名证书）
        //HttpsUtils.SSLParams sslParams3 = HttpsUtils.getSslSocketFactory(getAssets().open("srca.cer"));
        //方法四：使用bks证书和密码管理客户端证书（双向认证），使用预埋证书，校验服务端证书（自签名证书）
        //HttpsUtils.SSLParams sslParams4 = HttpsUtils.getSslSocketFactory(getAssets().open("xxx.bks"), "123456", getAssets().open("yyy.cer"));
        //builder.sslSocketFactory(sslParams1.sSLSocketFactory, sslParams1.trustManager);
        //配置https的域名匹配规则，详细看demo的初始化介绍，不需要就不要加入，使用不当会导致https握手失败
        //builder.hostnameVerifier(new SafeHostnameVerifier());
        //-----------------------------------------------------------------------------------------其他统一的配置
        // 详细说明看GitHub文档：https://github.com/jeasonlzy/
        OkGo.getInstance().init(this)                           //必须调用初始化
                .setOkHttpClient(builder.build())               //建议设置OkHttpClient，不设置会使用默认的
                .setCacheMode(CacheMode.NO_CACHE)               //全局统一缓存模式，默认不使用缓存，可以不传
                .setCacheTime(CacheEntity.CACHE_NEVER_EXPIRE)   //全局统一缓存时间，默认永不过期，可以不传
                .setRetryCount(0)                               //全局统一超时重连次数，默认为三次，那么最差的情况会请求4次(一次原始请求，三次重连请求)，不需要可以设置为0
                .addCommonHeaders(headers)                      //全局公共头
                .addCommonParams(params);                       //全局公共参数
    }
}
