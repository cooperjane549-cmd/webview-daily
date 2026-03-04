package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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

        // 1. Initialize Ads for DailyHub KE
        MobileAds.initialize(this, initializationStatus -> {});
        
        // Persistent Banner Ad
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }

        // Show Interstitial on startup
        loadInterstitial(true);

        // 2. WebView Setup
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // Identity for DailyHub KE
        settings.setUserAgentString(settings.getUserAgentString() + " DailyHubKE_App");

        // 3. The Bridge (Redirects user to Browser after Ad)
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void triggerAdAndRedirect(String url) {
                runOnUiThread(() -> {
                    showAdAndOpenBrowser(url);
                });
            }
        }, "DailyHubBridge");

        // Handle standard tools via redirect
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            showAdAndOpenBrowser(url);
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block Monetag/Amskiploomr to keep user safe
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
                startActivityForResult(Intent.createChooser(intent, "Upload to DailyHub"), FILE_CHOOSER_REQUEST_CODE);
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
                            loadInterstitial(false); // Reload for next use
                        }
                    });
                    if (showImmediately) mInterstitialAd.show(MainActivity.this);
                }
            });
    }

    private void showAdAndOpenBrowser(String url) {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(this);
            mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    mInterstitialAd = null;
                    loadInterstitial(false);
                    launchBrowser(url);
                }
            });
        } else {
            launchBrowser(url);
            loadInterstitial(false);
        }
    }

    private void launchBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            // Error handling
        }
    }

    private void injectRedirectListener() {
        // Intercepts click events on download buttons
        String js = "javascript:(function() {" +
                "  window.addEventListener('click', function(e) {" +
                "    var el = e.target.closest('a, button');" +
                "    if(!el) return;" +
                "    var text = el.innerText ? el.innerText.toLowerCase() : '';" +
                "    if(text.includes('download') || text.includes('generate')) {" +
                "      var targetUrl = el.href || window.location.href;" +
                "      DailyHubBridge.triggerAdAndRedirect(targetUrl);" +
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

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }
                                                    }
                            
