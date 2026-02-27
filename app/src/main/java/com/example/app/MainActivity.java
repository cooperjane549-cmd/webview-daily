package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.ProgressBar;
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
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private InterstitialAd mInterstitialAd;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 1. ADS & UI INITIALIZATION ---
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());
        loadInterstitial();

        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);

        // --- 2. WEBVIEW SETTINGS ---
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // Identify the app to your server to help bypass ads on the site side
        settings.setUserAgentString(settings.getUserAgentString() + " MatchaApp");

        // --- 3. DOWNLOAD BRIDGE ---
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName) {
                String name = (fileName == null || fileName.isEmpty() || fileName.equals("undefined")) 
                               ? "DailyHub_" + System.currentTimeMillis() + ".pdf" : fileName;
                saveToDownloads(base64Data, name);
            }
        }, "AndroidDownloader");

        // --- 4. WEBVIEW CLIENT (AD BLOCKING) ---
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // BLOCK MONETAG & REDIRECT ADS
                if (url.contains("amskiploomr.com") || url.contains("monetag") || url.contains("popads")) {
                    return true; 
                }

                if (!url.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return true;
                    } catch (Exception e) { return false; }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectScripts();
            }
        });

        // --- 5. WEBCHROME CLIENT (PROGRESS & UPLOADS) ---
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> f, FileChooserParams p) {
                filePathCallback = f;
                startActivityForResult(p.createIntent(), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void injectScripts() {
        // This script intercepts the 'Generate' trigger even if ads try to block it
        String js = "javascript:(function() {" +
                "  var originalCreate = URL.createObjectURL;" +
                "  URL.createObjectURL = function(blob) {" +
                "    var url = originalCreate.call(URL, blob);" +
                "    if(blob.type === 'application/pdf') {" +
                "       var r = new FileReader();" +
                "       r.onloadend = function() { AndroidDownloader.downloadBlob(r.result.split(',')[1], 'DailyHub_Doc.pdf'); };" +
                "       r.readAsDataURL(blob);" +
                "    }" +
                "    return url;" +
                "  };" +
                "  document.addEventListener('click', function(e) {" +
                "    var a = e.target.closest('a');" +
                "    if(a && a.href.startsWith('blob:')) {" +
                "      e.stopImmediatePropagation();" +
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
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveToDownloads(String base64, String name) {
        try {
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "Matcha PDF", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> Toast.makeText(this, "Success: Saved to Downloads", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download Error", Toast.LENGTH_SHORT).show());
        }
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
