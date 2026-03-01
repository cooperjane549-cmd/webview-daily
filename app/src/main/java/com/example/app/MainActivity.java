package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
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

        // 1. Initialize AdMob (App ID: ca-app-pub-2344867686796379~2770827433)
        MobileAds.initialize(this, initializationStatus -> {});
        
        // Load Banner Ad
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }
        
        loadInterstitial(); // Load Interstitial for later

        // 2. Setup WebView and Progress Bar
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(settings.getUserAgentString() + " MatchaApp");

        // 3. Javascript Bridge for Downloads
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName) {
                saveFileAndTriggerAd(base64Data, fileName != null ? fileName : "DailyHub_Doc.pdf");
            }
        }, "AndroidDownloader");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block Monetag Ad Redirects
                if (url.contains("amskiploomr.com") || url.contains("monetag")) return true;
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectStreamCatcher();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }

            // ✅ FIXED: MULTI-FILE SUPPORT FOR MERGE TOOL
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = params.createIntent();
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Allow 5+ files
                intent.setType("application/pdf");
                startActivityForResult(Intent.createChooser(intent, "Select PDF files"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    // ✅ FIXED: HANDLING THE UPLOAD ARRAY FOR THE MERGE TOOL
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) { // Multiple files
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) { // Single file
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private void injectStreamCatcher() {
        String js = "javascript:(function() {" +
                "  function sendToJava(url, name) {" +
                "    fetch(url).then(r => r.blob()).then(blob => {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() { AndroidDownloader.downloadBlob(reader.result.split(',')[1], name); };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                "  window.onclick = function(e) {" +
                "    var a = e.target.closest('a');" +
                "    if(a && a.href.startsWith('blob:')) {" +
                "      e.preventDefault(); e.stopImmediatePropagation();" +
                "      sendToJava(a.href, a.download);" +
                "    }" +
                "  };" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFileAndTriggerAd(String base64, String name) {
        try {
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "Matcha PDF", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> {
                Toast.makeText(this, "Success: File Saved", Toast.LENGTH_SHORT).show();
                if (mInterstitialAd != null) {
                    mInterstitialAd.show(MainActivity.this);
                    loadInterstitial();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, "ca-app-pub-2344867686796379/4612206920",
            new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) { mInterstitialAd = ad; }
            });
    }
}
