package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private WebView mWebView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private InterstitialAd mInterstitialAd;
    private final String INTERSTITIAL_ID = "ca-app-pub-2344867686796379/4612206920";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MobileAds.initialize(this, status -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());
        loadInterstitial();

        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);

        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setDatabaseEnabled(true);

        // THE BRIDGE: Handles Blobs from CV/Invoice tools to prevent crashes
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveBlob(String base64, String name) {
                runOnUiThread(() -> showInterstitial());
                new Thread(() -> {
                    try {
                        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
                        try (FileOutputStream fos = new FileOutputStream(path)) { fos.write(bytes); }
                        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        dm.addCompletedDownload(name, "DailyHub Document", true, "application/pdf", path.getAbsolutePath(), bytes.length, true);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved to Downloads", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download Failed", Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
        }, "DailyHubBridge");

        // THE DOWNLOADER: Handles standard links
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("blob:")) {
                // If it's a blob link, trigger the JS bridge to extract it
                mWebView.evaluateJavascript("javascript:fetch('" + url + "').then(r=>r.blob()).then(b=>{var reader=new FileReader();reader.onloadend=()=>DailyHubBridge.saveBlob(reader.result.split(',')[1],'DailyHub_File.pdf');reader.readAsDataURL(b);})", null);
            } else {
                showInterstitial();
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DailyHub_Doc.pdf");
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(p);
            }

            @Override // FIXED: Re-added the upload feature
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> f, FileChooserParams p) {
                filePathCallback = f;
                startActivityForResult(p.createIntent(), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.setWebViewClient(new WebViewClient());
        mWebView.loadUrl("https://dailyhubke.com");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(InterstitialAd ad) { mInterstitialAd = ad; }
        });
    }

    private void showInterstitial() {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(this);
            mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() { mInterstitialAd = null; loadInterstitial(); }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }
}
