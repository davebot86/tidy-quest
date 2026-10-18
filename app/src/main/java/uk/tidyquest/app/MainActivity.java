package uk.tidyquest.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import android.net.Uri;
import android.content.ContentValues;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_FILE = 2;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private PermissionRequest pendingPermission;
    private ValueCallback<Uri[]> fileCallback;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.UK);
                tts.setSpeechRate(0.95f);
            }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f3f1ec"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "*/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && accept[0] != null) {
                    if (accept[0].startsWith("image/") && accept.length == 1) type = "image/*";
                    else if (accept[0].startsWith("video/") && accept.length == 1) type = "video/*";
                }
                i.setType(type);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void speak(String text) {
                if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tq");
            }

            @JavascriptInterface
            public void share(String text) {
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, "Send the shop"));
                });
            }

            @JavascriptInterface
            public String makeQR(String text) {
                try {
                    Map<EncodeHintType, Object> hints = new HashMap<>();
                    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
                    hints.put(EncodeHintType.MARGIN, 1);
                    Bitmap bmp = new BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 640, 640, hints);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                } catch (Exception e) {
                    return "";
                }
            }

            @JavascriptInterface
            public void scanQR() {
                runOnUiThread(() -> new IntentIntegrator(MainActivity.this)
                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                        .setPrompt("Point at the Tidy Quest code on the other tablet")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                        .initiateScan());
            }

            @JavascriptInterface
            public boolean saveMedia(String base64, String mime, String name) {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    boolean video = mime != null && mime.startsWith("video");
                    String type = mime == null || mime.isEmpty() ? (video ? "video/webm" : "image/jpeg") : mime.split(";")[0];
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Tidy Quest");
                        Uri uri = getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) return false;
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            out.write(data);
                        }
                        return true;
                    }
                    if (!has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3));
                        return false;
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Tidy Quest");
                    if (!dir.exists() && !dir.mkdirs()) return false;
                    File f = new File(dir, name);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @JavascriptInterface
            public boolean fileBegin(String name) {
                try {
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists() && !dir.mkdirs()) return false;
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    shareFile = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", ""));
                    shareOut = new FileOutputStream(shareFile);
                    return true;
                } catch (Exception e) { return false; }
            }

            @JavascriptInterface
            public boolean fileAppend(String text) {
                try { shareOut.write(text.getBytes("UTF-8")); return true; } catch (Exception e) { return false; }
            }

            @JavascriptInterface
            public boolean fileShare(String title) {
                try { shareOut.close(); } catch (Exception ignored) {}
                return startShare(title);
            }

            @JavascriptInterface
            public boolean fileReshare(String title) {
                return shareFile != null && shareFile.exists() && startShare(title);
            }

            @JavascriptInterface
            public void copy(String text) {
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Tidy Quest shop", text));
                });
            }
        }, "AndroidTTS");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    private File shareFile;
    private FileOutputStream shareOut;

    private boolean startShare(String title) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", shareFile);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, "Open this in Tidy Quest: Grown-ups, Link tablets, Receive from a file.");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, title)));
            return true;
        } catch (Exception e) { return false; }
    }

    private void handlePermission(PermissionRequest request) {
        ArrayList<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA))
                needed.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO))
                needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingPermission = request;
            requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
        }
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS || pendingPermission == null) return;
        ArrayList<String> grant = new ArrayList<>();
        for (String r : pendingPermission.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) grant.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) grant.add(r);
        }
        if (grant.isEmpty()) pendingPermission.deny();
        else pendingPermission.grant(grant.toArray(new String[0]));
        pendingPermission = null;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        IntentResult scan = IntentIntegrator.parseActivityResult(code, result, data);
        if (scan != null) {
            String text = scan.getContents();
            String arg = text == null ? "null" : JSONObject.quote(text);
            web.evaluateJavascript("window.__onScan && window.__onScan(" + arg + ")", null);
            return;
        }
        if (code == REQ_FILE && fileCallback != null) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null && data.getData() != null) uris = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
            return;
        }
        super.onActivityResult(code, result, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? window.appBack() : 'exit'", value -> {
            if (value == null || value.contains("exit")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
