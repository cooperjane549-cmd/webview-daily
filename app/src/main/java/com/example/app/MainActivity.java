package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View; // Fixed the "symbol: variable View" error
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
import com.google.android.gms.ads.FullScreenContentCallback;
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

        // 1. ADS: Initialize and Persistent Banner
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }

        // Show Ad on startup for immediate revenue
        loadInterstitial(true);

        // 2. WEBVIEW SETUP
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // Brand the app in the User Agent
        settings.setUserAgentString(settings.getUserAgentString() + " DailyHubKE_App");

        // 3. THE BRIDGE: For "Invisible" Blob files (CV Maker, Invoices)
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadFile(String base64, String name) {
                // Show Ad on UI thread, process file on Background thread to prevent crashes
                runOnUiThread(() -> showInterstitialIfReady());
                new Thread(() -> saveDailyHubFile(base64, name)).start();
            }
        }, "DailyHubBridge");

        // 4. DOWNLOAD LISTENER: For standard tools (Merge PDF, Scan to PDF)
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            showInterstitialIfReady();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DailyHub_" + System.currentTimeMillis() + ".pdf");
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject the script that hijacks the 'Download' buttons to stay in-app
                injectDailyHubScript();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                // Visibility fixed with android.view.View import
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(p);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> f, FileChooserParams p) {
                filePathCallback = f;
                Intent intent = p.createIntent();
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Fixes Merge tool multi-upload
                startActivityForResult(Intent.createChooser(intent, "Select Files"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void loadInterstitial(boolean showImmediately) {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) {
                    mInterstitialAd = ad;
                    mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            mInterstitialAd = null;
                            loadInterstitial(false); // Reload immediately for business loop
                        }
                    });
                    if (showImmediately) mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    private void showInterstitialIfReady() {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(this);
        } else {
            loadInterstitial(false);
        }
    }

    private void injectDailyHubScript() {
        // Intercepts clicks and forces Blobs through the Bridge without refreshing the page
        String js = "javascript:(function() {" +
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText ? el.innerText.toLowerCase() : '';" +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      if(el.href && el.href.startsWith('blob:')) {" +
                "        e.preventDefault(); e.stopImmediatePropagation();" +
                "        fetch(el.href).then(r => r.blob()).then(blob => {" +
                "          var reader = new FileReader();" +
                "          reader.onloadend = function() {" +
                "            DailyHubBridge.downloadFile(reader.result.split(',')[1], el.download || 'DailyHub_Doc.pdf');" +
                "          };" +
                "          reader.readAsDataURL(blob);" +
                "        });" +
                "      }" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveDailyHubFile(String base64, String name) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name != null ? name : "DailyHub_Doc.pdf");
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); os.flush(); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub KE", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            runOnUiThread(() -> Toast.makeText(this, "File Saved to Downloads", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download error", Toast.LENGTH_SHORT).show());
        }
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

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }
    }
                
