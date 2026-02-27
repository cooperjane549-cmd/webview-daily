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

public class MainActivity extends Activity {

    private WebView mWebView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private InterstitialAd mInterstitialAd;
    private AdView mBannerAd;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize AdMob
        MobileAds.initialize(this, initializationStatus -> {});

        // Banner
        mBannerAd = findViewById(R.id.adView);
        mBannerAd.loadAd(new AdRequest.Builder().build());

        // Interstitial
        InterstitialAd.load(this,
                "ca-app-pub-2344867686796379/4612206920",
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        mInterstitialAd = ad;
                        mInterstitialAd.show(MainActivity.this);
                    }
                });

        // WebView setup
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);

        // Add JS interface for Blob downloads
        mWebView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void downloadFile(String base64Data, String fileName) {
                try {
                    byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    File path = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                    FileOutputStream fos = new FileOutputStream(path);
                    fos.write(data);
                    fos.close();

                    Toast.makeText(MainActivity.this, "Downloaded: " + fileName, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                }
            }
        }, "AndroidDownloader");

        // WebViewClient
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Block Monetag
                if (url.contains("amskiploomr.com")) return true;

                // Handle intent:// links
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        startActivity(intent);
                    } catch (Exception e) { e.printStackTrace(); }
                    return true;
                }

                // Handle tel:, mailto:, market:
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("market:")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }

                return false; // Allow normal URLs
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Inject JS to handle Blob downloads
                String js = "javascript:(function() {" +
                        "var links = document.querySelectorAll('a');" +
                        "for(var i=0;i<links.length;i++){" +
                        " links[i].addEventListener('click',function(e){" +
                        "  if(this.href.startsWith('blob:')){" +
                        "   e.preventDefault();" +
                        "   var xhr = new XMLHttpRequest();" +
                        "   xhr.open('GET',this.href,true);" +
                        "   xhr.responseType='blob';" +
                        "   xhr.onload=function(){" +
                        "    var reader=new FileReader();" +
                        "    reader.onloadend=function(){" +
                        "     var base64=reader.result.split(',')[1];" +
                        "     AndroidDownloader.downloadFile(base64,'invoice.pdf');" +
                        "    };" +
                        "    reader.readAsDataURL(xhr.response);" +
                        "   };" +
                        "   xhr.send();" +
                        "  }" +
                        " });" +
                        "}" +
                        "})()";

                view.evaluateJavascript(js, null);
            }
        });

        // WebChromeClient for file upload
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null)
                    MainActivity.this.filePathCallback.onReceiveValue(null);

                MainActivity.this.filePathCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }

                return true;
            }
        });

        // Normal HTTP/HTTPS download (non-blob)
        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                    Toast.makeText(MainActivity.this, "Please allow storage permission to download files", Toast.LENGTH_SHORT).show();
                    return;
                }
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);

            Toast.makeText(MainActivity.this, "Download started: " + fileName, Toast.LENGTH_SHORT).show();
        });

        // Load main URL
        mWebView.loadUrl("https://dailyhubke.com");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;

            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else {
                    result = new Uri[]{data.getData()};
                }
            }

            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Permission denied. Cannot download files.", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Permission granted! You can now download files.", Toast.LENGTH_SHORT).show();
        }
    }
}
