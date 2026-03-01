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

        // 1. Initialize AdMob and Banner
        MobileAds.initialize(this, initializationStatus -> {});
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }

        // 2. Load App Open Ad
        loadAndShowAppOpenAd();

        // 3. WebView Configuration
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // Use DailyHub KE identity
        String currentUA = settings.getUserAgentString();
        settings.setUserAgentString(currentUA + " DailyHubKE_App");

        // 4. JavaScript Bridge for Downloads
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64, String name) {
                saveFile(base64, name != null ? name : "DailyHub_Doc.pdf");
            }
            
            @JavascriptInterface
            public void triggerDownloadAd() {
                runOnUiThread(() -> showInterstitialNow());
            }
        }, "AndroidDownloader");

        // 5. Standard Download Listener
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            runOnUiThread(() -> showInterstitialNow());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            startActivity(i);
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block Monetag ad redirects
                if (url.contains("monetag") || url.contains("amskiploomr")) return true;
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectGlobalListener(); // Re-inject on every page load
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
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Support for Merge PDF tool
                startActivityForResult(Intent.createChooser(intent, "Select PDF Files"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void loadAndShowAppOpenAd() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) {
                    mInterstitialAd = ad;
                    mInterstitialAd.show(MainActivity.this);
                    loadNextInterstitial(); 
                }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError e) { loadNextInterstitial(); }
            });
    }

    private void loadNextInterstitial() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) { mInterstitialAd = ad; }
            });
    }

    private void showInterstitialNow() {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(MainActivity.this);
            loadNextInterstitial();
        }
    }

    private void injectGlobalListener() {
        // This script intercepts clicks on "Download" or "Generate" buttons
        // It specifically handles "Blob" URLs used by modern PDF generators
        String js = "javascript:(function() {" +
                "  function processBlob(url, name) {" +
                "    fetch(url).then(r => r.blob()).then(blob => {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() {" +
                "        AndroidDownloader.downloadBlob(reader.result.split(',')[1], name || 'DailyHub_Doc.pdf');" +
                "      };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                "  window.addEventListener('click', function(e) {" +
                "    var target = e.target.closest('a, button');" +
                "    if(!target) return;" +
                "    var text = target.innerText ? target.innerText.toLowerCase() : '';" +
                "    " +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      AndroidDownloader.triggerDownloadAd();" +
                "    }" +
                "    " +
                "    if(target.href && target.href.startsWith('blob:')) {" +
                "      e.preventDefault(); e.stopImmediatePropagation();" +
                "      processBlob(target.href, target.download || 'DailyHub_Doc.pdf');" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFile(String base64, String name) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { 
                os.write(data); 
                os.flush();
            }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub Download", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> Toast.makeText(this, "File Downloaded Successfully", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show());
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
