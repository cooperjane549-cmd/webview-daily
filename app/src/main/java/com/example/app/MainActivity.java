package com.example.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView mWebView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    
    private InterstitialAd mInterstitialAd;
    private final String INTERSTITIAL_ID = "ca-app-pub-2344867686796379/4612206920";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize AdMob (APP ID: ca-app-pub-2344867686796379~2770827433)
        MobileAds.initialize(this, initializationStatus -> {});
        
        // Load Top Banner
        AdView mBannerAd = findViewById(R.id.adView);
        if (mBannerAd != null) {
            mBannerAd.loadAd(new AdRequest.Builder().build());
        }
        
        // Initial load of Interstitial
        loadInterstitial();

        // 2. Setup WebView & Progress Bar
        progressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.activity_main_webview);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Identify app to server
        settings.setUserAgentString(settings.getUserAgentString() + " MatchaApp");

        // 3. The "Boss" Download Bridge
        mWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadBlob(String base64Data, String fileName) {
                saveFileAndShowAd(base64Data, fileName);
            }
        }, "AndroidDownloader");

        // 4. Client Logic (Ad Blocking + Redirect Handling)
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Block Monetag Ads
                if (url.contains("amskiploomr.com") || url.contains("monetag")) return true;

                if (!url.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return true;
                    } catch (Exception e) { return false; }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectStreamCatcher();
            }
        });

        // 5. Chrome Client (Camera + File Uploads + Progress)
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                MainActivity.this.filePathCallback = filePathCallback;
                
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                    File photoFile = null;
                    try {
                        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                        photoFile = File.createTempFile("JPEG_" + timeStamp + "_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES));
                        cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                    } catch (Exception ignored) {}

                    if (photoFile != null) {
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
                    } else {
                        takePictureIntent = null;
                    }
                }

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("*/*");

                Intent[] intentArray = takePictureIntent != null ? new Intent[]{takePictureIntent} : new Intent[0];
                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Upload Document or Take Photo");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        mWebView.loadUrl("https://dailyhubke.com");
    }

    private void injectStreamCatcher() {
        // This script intercepts FETCH requests and window.open calls to grab PDFs
        String js = "javascript:(function() {" +
                "  function sendToJava(url, name) {" +
                "    fetch(url).then(r => r.blob()).then(blob => {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() { AndroidDownloader.downloadBlob(reader.result.split(',')[1], name || 'DailyHub_Doc.pdf'); };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                "  window.onclick = function(e) {" +
                "    var a = e.target.closest('a');" +
                "    if(a && (a.href.startsWith('blob:') || a.download.endsWith('.pdf'))) {" +
                "      e.preventDefault(); e.stopImmediatePropagation();" +
                "      sendToJava(a.href, a.download);" +
                "    }" +
                "  };" +
                "  var originalOpen = window.open;" +
                "  window.open = function(url) {" +
                "    if(url && url.startsWith('blob:')) { sendToJava(url, 'Document.pdf'); return null; }" +
                "    return originalOpen(url);" +
                "  };" +
                "})()";
        mWebView.evaluateJavascript(js, null);
    }

    private void saveFileAndShowAd(String base64, String name) {
        try {
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (OutputStream os = new FileOutputStream(path)) { os.write(data); }
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.addCompletedDownload(name, "DailyHub Document", true, "application/pdf", path.getAbsolutePath(), data.length, true);
            
            runOnUiThread(() -> {
                Toast.makeText(this, "Download Complete!", Toast.LENGTH_SHORT).show();
                // Trigger Interstitial after download
                if (mInterstitialAd != null) {
                    mInterstitialAd.show(MainActivity.this);
                    loadInterstitial(); // Preload next one
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void loadInterstitial() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) { mInterstitialAd = ad; }
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) { mInterstitialAd = null; }
            });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data == null || data.getData() == null) {
                    if (cameraPhotoPath != null) results = new Uri[]{Uri.parse(cameraPhotoPath)};
                } else {
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
