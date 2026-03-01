package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

public class MainActivity extends Activity {

    private WebView mWebView;
    private AdView bannerAd;
    private InterstitialAd interstitialAd;
    private ValueCallback<Uri[]> filePathCallback;

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 🔹 Initialize AdMob
        MobileAds.initialize(this, initializationStatus -> {});

        // 🔹 Banner Ad
        bannerAd = findViewById(R.id.adView);
        AdRequest bannerRequest = new AdRequest.Builder().build();
        bannerAd.loadAd(bannerRequest);

        // 🔹 Load first interstitial
        loadInterstitial();

        // 🔹 WebView Setup
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 🔹 JS interface for blob downloads
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBase64(String base64Data, String fileName) {
                try {
                    byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);

                    File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();

                    Toast.makeText(MainActivity.this, "PDF Downloaded", Toast.LENGTH_SHORT).show();

                    // 🔥 SHOW INTERSTITIAL AFTER PDF GENERATED
                    showInterstitial();

                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                }
            }
        }, "Android");

        mWebView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Block Monetag intent
                if (url.contains("amskiploomr.com")) {
                    return true;
                }

                if (url.startsWith("intent://")) {
                    return true;
                }

                if (url.startsWith("tel:") || url.startsWith("mailto:")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }

                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Inject JS for blob downloads
                String js = "javascript:(function() {" +
                        "document.querySelectorAll('a').forEach(function(link){" +
                        " link.addEventListener('click', function(e){" +
                        "  if(this.href.startsWith('blob:')){" +
                        "   e.preventDefault();" +
                        "   var xhr = new XMLHttpRequest();" +
                        "   xhr.open('GET', this.href);" +
                        "   xhr.responseType='blob';" +
                        "   xhr.onload=function(){" +
                        "    var reader=new FileReader();" +
                        "    reader.onloadend=function(){" +
                        "     var base64=reader.result.split(',')[1];" +
                        "     Android.downloadBase64(base64,'invoice.pdf');" +
                        "    };" +
                        "    reader.readAsDataURL(xhr.response);" +
                        "   };" +
                        "   xhr.send();" +
                        "  }" +
                        " });" +
                        "});" +
                        "})()";

                view.evaluateJavascript(js, null);
            }
        });

        // Normal HTTP download
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {

            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            request.setDestinationInExternalFilesDir(
                    MainActivity.this,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);

            Toast.makeText(MainActivity.this, "Downloading...", Toast.LENGTH_SHORT).show();

            // 🔥 SHOW INTERSTITIAL AFTER NORMAL PDF DOWNLOAD
            showInterstitial();
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    // 🔹 Load Interstitial
    private void loadInterstitial() {
        AdRequest request = new AdRequest.Builder().build();
        InterstitialAd.load(this,
                "ca-app-pub-2344867686796379/4612206920",
                request,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                    }
                });
    }

    // 🔹 Show Interstitial
    private void showInterstitial() {
        if (interstitialAd != null) {
            interstitialAd.show(this);
            loadInterstitial(); // preload next one
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
