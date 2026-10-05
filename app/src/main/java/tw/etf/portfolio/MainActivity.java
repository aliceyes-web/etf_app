package tw.etf.portfolio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外殼 App：用 WebView 載入 assets/index.html（同一份網頁 App），
 * 並提供原生橋接：
 *   - httpGet：不受瀏覽器跨網域限制的 HTTP GET（只允許證交所／櫃買的網域）
 *   - saveFile：把備份 JSON 寫進「下載」資料夾
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1001;
    private static final long MIS_COOKIE_TTL_MS = 5 * 60 * 1000L;

    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "mis.twse.com.tw",
            "openapi.twse.com.tw",
            "www.twse.com.tw",
            "www.tpex.org.tw"));

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Object misLock = new Object();
    private long misCookieAt = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // HttpURLConnection 會用這個 handler 保存 cookie（證交所即時報價需要先取得 session）
        CookieHandler.setDefault(new java.net.CookieManager());

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int barColor = night ? 0xFF171B22 : Color.WHITE;
        getWindow().setStatusBarColor(barColor);
        getWindow().setNavigationBarColor(barColor);
        if (!night) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // 沒有可開啟的 App 就略過
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /** 提供給網頁呼叫的原生功能 */
    private class Bridge {

        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String body;
                    boolean ok;
                    try {
                        body = doGet(url);
                        ok = true;
                    } catch (Exception e) {
                        body = String.valueOf(e.getMessage());
                        ok = false;
                    }
                    final String js = "window.__nativeCb(" + JSONObject.quote(id) + ","
                            + ok + "," + JSONObject.quote(body) + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String content) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
                        ContentResolver resolver = getContentResolver();
                        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) {
                            throw new IOException("無法建立檔案");
                        }
                        OutputStream os = resolver.openOutputStream(uri);
                        if (os == null) {
                            throw new IOException("無法寫入檔案");
                        }
                        try {
                            os.write(content.getBytes(StandardCharsets.UTF_8));
                        } finally {
                            os.close();
                        }
                        Toast.makeText(MainActivity.this,
                                "已儲存到「下載」資料夾：" + name, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "儲存失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private String doGet(String urlStr) throws Exception {
        URL u = new URL(urlStr);
        if (!"https".equals(u.getProtocol()) || !ALLOWED_HOSTS.contains(u.getHost())) {
            throw new IOException("不允許的網址");
        }
        if ("mis.twse.com.tw".equals(u.getHost())) {
            synchronized (misLock) {
                long now = System.currentTimeMillis();
                if (now - misCookieAt > MIS_COOKIE_TTL_MS) {
                    try {
                        rawGet("https://mis.twse.com.tw/stock/index.jsp");
                    } catch (Exception ignored) {
                        // 取 cookie 失敗時仍嘗試直接查詢
                    }
                    misCookieAt = now;
                }
            }
        }
        return rawGet(urlStr);
    }

    private String rawGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (urlStr.contains("mis.twse.com.tw")) {
            c.setRequestProperty("Referer", "https://mis.twse.com.tw/stock/fibest.jsp");
        }
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__app&&window.__app.back&&window.__app.back());}"
                        + "catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value)) {
                            finish();
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
