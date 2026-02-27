package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView mWebView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private InterstitialAd mInterstitialAd;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize AdMob (Banner & Interstitial)
        MobileAds.initialize(this, initializationStatus -> {});
        
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }

        loadInterstitial();

        // 2. WebView Core Configuration
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true); 

        // 3. The Download Bridge (Blob & Base64)
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName) {
                String finalName = (fileName == null || fileName.isEmpty() || fileName.equals("undefined")) 
                                   ? "DailyHub_" + System.currentTimeMillis() + ".pdf" 
                                   : fileName;
                saveFileToPublicDownloads(base64Data, finalName);
            }
        }, "AndroidDownloader");

        // 4. Client to handle Redirects and Ad-Blocking
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // Block specific ad domains if needed
                if (url.contains("amskiploomr.com")) return true;

                // Handle external links (WhatsApp, Email, Phone)
                if (!url.startsWith("http")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    } catch (Exception e) { return false; }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectDownloadScripts();
            }
        });

        // 5. Chrome Client for File Uploads and New Window Popups
        mWebView.setWebChromeClient(new WebChromeClient() {
            // File Upload Support (Merge PDF / Scan to PDF)
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }

            // Handles tools that open PDFs in new tabs
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView newWebView = new WebView(MainActivity.this);
                newWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        mWebView.loadUrl(request.getUrl().toString());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // 6. Handle Standard HTTP Downloads
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            handleStandardDownload(url, contentDisposition, mimeType);
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    // --- HELPER: JavaScript Injection for Blobs ---
    private void injectDownloadScripts() {
        String js = "javascript:(function() {" +
                "  var originalCreate = URL.createObjectURL;" +
                "  URL.createObjectURL = function(blob) {" +
                "    var url = originalCreate.call(URL, blob);" +
                "    if(blob.type === 'application/pdf') {" +
                "       var reader = new FileReader();" +
                "       reader.onloadend = function() {" +
                "         AndroidDownloader.downloadBlob(reader.result.split(',')[1], 'DailyHub_Document.pdf');" +
                "       };" +
                "       reader.readAsDataURL(blob);" +
                "    }" +
                "    return url;" +
                "  };" +
                "  window.onclick = function(e) {" +
                "    var a = e.target.closest('a');" +
                "    if(a && a.href.startsWith('blob:')) {" +
                "      e.preventDefault();" +
                "      var xhr = new XMLHttpRequest();" +
                "      xhr.open('GET', a.href, true);" +
                "      xhr.responseType = 'blob';" +
                "      xhr.onload = function() {" +
                "        var r = new FileReader();" +
                "        r.onloadend = function() { AndroidDownloader.downloadBlob(r.result.split(',')[1], a.download); };" +
                "        r.readAsDataURL(xhr.response);" +
                "      };" +
                "      xhr.send();" +
                "    }" +
                "  };" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    // --- HELPER: Save to Public Downloads ---
    private void saveFileToPublicDownloads(String base64, String name) {
        try {
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub Document", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> Toast.makeText(this, "Downloaded to " + name, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download Failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void handleStandardDownload(String url, String contentDisposition, String mimeType) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        dm.enqueue(request);
        Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show();
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, "ca-app-pub-2344867686796379/4612206920",
            new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) {
                    mInterstitialAd = ad;
                    mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }
}
