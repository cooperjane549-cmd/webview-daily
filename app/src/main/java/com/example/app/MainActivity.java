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
    private final String INTERSTITIAL_ID = "ca-app-pub-2344867686796379/4612206920";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize AdMob & Banner
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());
        loadInterstitial();

        // 2. Setup WebView
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(settings.setUserAgentString() + " MatchaApp");

        // 3. JS Bridge (The Bridge between Web Click and Android Ad/Download)
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64, String name) {
                saveFile(base64, name);
            }

            @JavascriptInterface
            public void triggerAdOnly() {
                runOnUiThread(() -> showAdIfReady());
            }
        }, "AndroidDownloader");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("monetag") || url.contains("amskiploomr")) return true; // Block ad hijacks
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectGlobalListener();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(p);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> f, FileChooserParams p) {
                filePathCallback = f;
                Intent intent = p.createIntent();
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Fix for Merge PDF
                startActivityForResult(Intent.createChooser(intent, "Select Files"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void injectGlobalListener() {
        String js = "javascript:(function() {" +
                "  function startDownload(url, name) {" +
                "    fetch(url).then(r => r.blob()).then(blob => {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() { AndroidDownloader.downloadBlob(reader.result.split(',')[1], name); };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                // Catch every click on the page
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText.toLowerCase();" +
                "    " +
                // If it's a download button, force the ad immediately
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      AndroidDownloader.triggerAdOnly();" +
                "    }" +
                "    " +
                // If it has a blob link, hijack it for the download
                "    if(el.href && el.href.startsWith('blob:')) {" +
                "      e.preventDefault(); e.stopImmediatePropagation();" +
                "      startDownload(el.href, el.download);" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFile(String base64, String name) {
        try {
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "Matcha PDF", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> {
                Toast.makeText(this, "File Saved to Downloads", Toast.LENGTH_SHORT).show();
                showAdIfReady(); // Show ad again on finish if ready
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download Failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void showAdIfReady() {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(MainActivity.this);
            loadInterstitial(); // Load next ad
        } else {
            loadInterstitial(); // Pre-fetch for next click
        }
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) { mInterstitialAd = ad; }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError e) { mInterstitialAd = null; }
            });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }
}
