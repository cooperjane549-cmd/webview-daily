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
import android.webkit.DownloadListener;
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

        MobileAds.initialize(this, initializationStatus -> {});
        
        // Banner Ad
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());

        // Initial Interstitial
        loadInterstitial(true);

        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // Ensure the website sees us as a modern browser
        settings.setUserAgentString(settings.getUserAgentString() + " DailyHubKE_App");

        // 1. THE BRIDGE
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64, String name) {
                // Background thread to handle the heavy processing
                new Thread(() -> saveFile(base64, name)).start();
            }
            
            @JavascriptInterface
            public void triggerAd() {
                runOnUiThread(() -> showInterstitialIfReady());
            }
        }, "AndroidDownloader");

        // 2. STANDARD DOWNLOADER (For Merge PDF, Scan to PDF)
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            runOnUiThread(() -> showInterstitialIfReady());
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DailyHub_" + System.currentTimeMillis() + ".pdf");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(this, "Downloading File...", Toast.LENGTH_SHORT).show();
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block Monetag redirects that steal the user away
                if (url.contains("monetag") || url.contains("amskiploomr")) return true;
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectSuperListener();
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
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // FIXES MERGE UPLOAD
                startActivityForResult(Intent.createChooser(intent, "Upload Files"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void loadInterstitial(boolean show) {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) {
                    mInterstitialAd = ad;
                    mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            mInterstitialAd = null;
                            loadInterstitial(false); // Reload for next download
                        }
                    });
                    if (show) mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    private void showInterstitialIfReady() {
        if (mInterstitialAd != null) mInterstitialAd.show(this);
        else loadInterstitial(false);
    }

    private void injectSuperListener() {
        // This is the strongest listener. It intercepts clicks AND monitors 
        // the browser for any new 'blob' URLs created in the background.
        String js = "javascript:(function() {" +
                "  function xfer(url, name) {" +
                "    fetch(url).then(r => r.blob()).then(blob => {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() { " +
                "        AndroidDownloader.downloadBlob(reader.result.split(',')[1], name || 'DailyHub_Doc.pdf'); " +
                "      };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText ? el.innerText.toLowerCase() : '';" +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      AndroidDownloader.triggerAd();" +
                "      if(el.href && el.href.startsWith('blob:')) {" +
                "        e.preventDefault(); e.stopImmediatePropagation();" +
                "        xfer(el.href, el.download);" +
                "      }" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFile(String base64, String name) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            String fileName = (name == null || name.isEmpty()) ? "DailyHub_Doc.pdf" : name;
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
            
            try (OutputStream os = new FileOutputStream(path)) { 
                os.write(data); 
                os.flush();
            }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(fileName, "DailyHub", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> Toast.makeText(this, "File Saved to Downloads", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Error Saving File", Toast.LENGTH_SHORT).show());
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
}
