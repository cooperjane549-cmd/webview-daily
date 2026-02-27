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
    private static final int PERMISSION_REQUEST_CODE = 100;
    private InterstitialAd mInterstitialAd;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize Ads
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        mBannerAd.loadAd(new AdRequest.Builder().build());
        loadInterstitial();

        // 2. WebView Setup
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 3. The "Blob" Downloader Interface
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName, String mimeType) {
                saveBlobToDownloads(base64Data, fileName, mimeType);
            }
        }, "AndroidDownloader");

        // 4. WebView Client Logic
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // Block Monetag or unwanted redirects
                if (url.contains("amskiploomr.com")) return true;

                // Handle external intents (tel, mail, apps)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
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
                injectBlobScript();
            }
        });

        // 5. Normal File Upload Support
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = params.createIntent();
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        // 6. Normal URL Download Listener (non-blob)
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            handleStandardDownload(url, contentDisposition, mimeType);
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    // --- HELPER METHODS ---

    private void injectBlobScript() {
        // This script intercepts clicks on blob links and converts them to Base64 for Java
        String js = "javascript:(function() {" +
                "window.onclick = function(e) {" +
                "  var link = e.target.closest('a');" +
                "  if(link && link.href.startsWith('blob:')) {" +
                "    e.preventDefault();" +
                "    var xhr = new XMLHttpRequest();" +
                "    xhr.open('GET', link.href, true);" +
                "    xhr.responseType = 'blob';" +
                "    xhr.onload = function() {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() {" +
                "        var base64 = reader.result.split(',')[1];" +
                "        var name = link.download || 'document.pdf';" +
                "        AndroidDownloader.downloadBlob(base64, name, 'application/pdf');" +
                "      };" +
                "      reader.readAsDataURL(xhr.response);" +
                "    };" +
                "    xhr.send();" +
                "  }" +
                "};" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveBlobToDownloads(String base64, String name, String mime) {
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            
            try (OutputStream os = new FileOutputStream(path)) {
                os.write(bytes);
            }

            // Make it show up in the Download Manager / Notifications
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub Document", true, mime, path.getAbsolutePath(), bytes.length, true);

            runOnUiThread(() -> Toast.makeText(this, "Download Complete: " + name, Toast.LENGTH_SHORT).show());
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
        Toast.makeText(this, "Starting Download...", Toast.LENGTH_SHORT).show();
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
