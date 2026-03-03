package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

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

        // 1. Initialize Mobile Ads
        MobileAds.initialize(this, initializationStatus -> {});
        
        // Load the persistent Banner Ad
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }

        // SHOW AD ON OPEN
        loadInterstitial(true);

        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // 2. THE BRIDGE (Triggers the Ad before Redirect)
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void triggerAdAndRedirect(String url) {
                runOnUiThread(() -> {
                    showInterstitialAndOpenBrowser(url);
                });
            }
        }, "AndroidDownloader");

        // 3. EXTERNAL BROWSER HANDLER (For standard links)
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            showInterstitialAndOpenBrowser(url);
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block monetization redirects to keep user in your business app
                if (url.contains("monetag") || url.contains("amskiploomr")) return true;
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectRedirectListener();
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
                startActivityForResult(Intent.createChooser(intent, "Upload Files"), FILE_CHOOSER_REQUEST_CODE);
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
                            loadInterstitial(false); // Pre-load next one for business revenue
                        }
                    });
                    if (showImmediately) mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    private void showInterstitialAndOpenBrowser(String url) {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(this);
            // Even if they close the ad, the intent will fire
            mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    mInterstitialAd = null;
                    loadInterstitial(false);
                    openInBrowser(url);
                }
            });
        } else {
            openInBrowser(url);
            loadInterstitial(false);
        }
    }

    private void openInBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            // Fallback if URL is a blob or invalid
        }
    }

    private void injectRedirectListener() {
        // This script watches for download clicks. 
        // If it finds a URL, it sends it to the Java Bridge to open in Chrome.
        String js = "javascript:(function() {" +
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText ? el.innerText.toLowerCase() : '';" +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      var targetUrl = el.href || window.location.href;" +
                "      AndroidDownloader.triggerAdAndRedirect(targetUrl);" +
                "    }" +
                "  }, true);" +
                "})()";
        mWebView.evaluateJavascript(js, null);
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
