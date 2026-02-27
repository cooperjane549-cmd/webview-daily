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

        // 1. Ads Initialization
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());
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
        settings.setSupportMultipleWindows(true); // Enabled to catch pop-up tools

        // 3. The "Everything" Downloader Interface
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName, String mimeType) {
                // If the site doesn't name the file, we name it by timestamp
                String finalName = (fileName == null || fileName.isEmpty() || fileName.equals("undefined")) 
                                   ? "DailyHub_" + System.currentTimeMillis() + ".pdf" 
                                   : fileName;
                saveBlobToDownloads(base64Data, finalName, mimeType);
            }
        }, "AndroidDownloader");

        // 4. Client to handle URL logic
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // Block Adsense/Redirect loops if necessary
                if (url.contains("amskiploomr.com")) return true;

                // Handle system intents (Phone, Email, Play Store)
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
                injectUniversalDownloader();
            }
        });

        // 5. Chrome Client for File Uploads and New Windows
        mWebView.setWebChromeClient(new WebChromeClient() {
            // This handles tools that try to open a new tab for the PDF
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

            // This handles the "Merge PDF" or "Scan to PDF" file uploads
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = params.createIntent();
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        // 6. Handle standard (non-blob) downloads
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            handleStandardDownload(url, contentDisposition, mimeType);
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    // --- LOGIC: The Universal Blob Interceptor ---
    private void injectUniversalDownloader() {
        String js = "javascript:(function() {" +
                "  function processBlob(url, fileName) {" +
                "    var xhr = new XMLHttpRequest();" +
                "    xhr.open('GET', url, true);" +
                "    xhr.responseType = 'blob';" +
                "    xhr.onload = function() {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() {" +
                "        var base64 = reader.result.split(',')[1];" +
                "        AndroidDownloader.downloadBlob(base64, fileName || 'document.pdf', 'application/pdf');" +
                "      };" +
                "      reader.readAsDataURL(xhr.response);" +
                "    };" +
                "    xhr.send();" +
                "  }" +
                // Catch clicks on download buttons
                "  window.onclick = function(e) {" +
                "    var link = e.target.closest('a');" +
                "    if(link && link.href.startsWith('blob:')) {" +
                "      e.preventDefault();" +
                "      processBlob(link.href, link.download);" +
                "    }" +
                "  };" +
                // Catch tools that use window.open()
                "  var oldOpen = window.open;" +
                "  window.open = function(url, name, specs) {" +
                "    if(url && url.startsWith('blob:')) {" +
                "      processBlob(url, 'document.pdf');" +
                "      return null;" +
                "    }" +
                "    return oldOpen(url, name, specs);" +
                "  };" +
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

            // Trigger system notification
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "Matcha Download", true, mime, path.getAbsolutePath(), bytes.length, true);

            runOnUiThread(() -> Toast.makeText(this, "Saved to Downloads: " + name, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download error", Toast.LENGTH_SHORT).show());
        }
    }

    private void handleStandardDownload(String url, String contentDisposition, String mimeType) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        dm.enqueue(request);
        Toast.makeText(this, "Downloading file...", Toast.LENGTH_SHORT).show();
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
