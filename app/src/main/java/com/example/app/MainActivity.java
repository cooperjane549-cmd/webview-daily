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

        // 1. ADS INITIALIZATION
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) mBannerAd.loadAd(new AdRequest.Builder().build());

        // SHOW AD ON OPEN
        loadInterstitial(true);

        // 2. WEBVIEW SETUP
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        settings.setUserAgentString(settings.getUserAgentString() + " DailyHubKE_App");

        // 3. THE BRIDGE
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64, String name) {
                new Thread(() -> saveFile(base64, name != null ? name : "DailyHub_Doc.pdf")).start();
            }
            @JavascriptInterface
            public void triggerAd() {
                runOnUiThread(() -> showInterstitialIfReady());
            }
        }, "AndroidDownloader");

        // 4. FIX FOR STANDARD TOOLS (Merge, Scan, etc.)
        mWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                showInterstitialIfReady();
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DailyHub_File.pdf");
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(MainActivity.this, "Downloading File...", Toast.LENGTH_SHORT).show();
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectHybridListener();
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
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); 
                startActivityForResult(Intent.createChooser(intent, "Select Files"), FILE_CHOOSER_REQUEST_CODE);
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
                            loadInterstitial(false); 
                        }
                    });
                    if (show) mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    private void showInterstitialIfReady() {
        if (mInterstitialAd != null) mInterstitialAd.show(MainActivity.this);
        else loadInterstitial(false);
    }

    private void injectHybridListener() {
        // This script is "smart" - it only hijacks blobs. 
        // Normal links are left for the DownloadListener to handle.
        String js = "javascript:(function() {" +
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText ? el.innerText.toLowerCase() : '';" +
                "    " +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      AndroidDownloader.triggerAd();" +
                "    }" +
                "    " +
                "    if(el.href && el.href.startsWith('blob:')) {" +
                "      e.preventDefault();" +
                "      fetch(el.href).then(r => r.blob()).then(blob => {" +
                "        var reader = new FileReader();" +
                "        reader.onloadend = function() {" +
                "          AndroidDownloader.downloadBlob(reader.result.split(',')[1], el.download || 'DailyHub_Doc.pdf');" +
                "        };" +
                "        reader.readAsAsDataURL(blob);" +
                "      });" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFile(String base64, String name) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); os.flush(); }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            runOnUiThread(() -> Toast.makeText(this, "Success: Saved to Downloads", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show());
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
                } else if (data.getData() != null) results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }
}
