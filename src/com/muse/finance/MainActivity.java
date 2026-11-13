package com.muse.finance;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    // Text shared to the app (e.g. a Sham Cash receipt): delivered to the
    // page as a draft after it loads, never saved directly.
    private String pendingSharedText;
    // File picker for the page's hidden file input (backup restore):
    // without this, tapping "restore" in a WebView does nothing at all.
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int FILE_CHOOSER_REQUEST = 100;

    // Native Android speech recognition, exposed to the page as the
    // Capacitor SpeechRecognition plugin the app already expects.
    // Android WebView has no Web Speech API, so without this the mic
    // button would always report "not available".
    public class VoiceBridge {
        @JavascriptInterface
        public void start() {
            runOnUiThread(() -> startListening());
        }
    }

    private void startListening() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                webView.evaluateJavascript("window.__voiceError()", null);
                return;
            }
            if (speechRecognizer != null) speechRecognizer.destroy();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle r) {}
                @Override public void onEvent(int e, Bundle b) {}
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
                    deliverVoiceResult(text);
                    destroyRecognizer();
                }
                @Override public void onError(int error) {
                    webView.evaluateJavascript("window.__voiceError()", null);
                    destroyRecognizer();
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            webView.evaluateJavascript("window.__voiceError()", null);
        }
    }

    private void deliverVoiceResult(String text) {
        try {
            String quoted = JSONObject.quote(text == null ? "" : text);
            webView.evaluateJavascript("window.__voiceResult(" + quoted + ")", null);
        } catch (Exception e) {
            webView.evaluateJavascript("window.__voiceError()", null);
        }
    }

    private void destroyRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyRecognizer();
        super.onDestroy();
    }

    public class FileSaver {
        @JavascriptInterface
        public void saveBase64(String base64, String filename) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String name = (filename == null || filename.isEmpty()) ? "finance-report.xlsx" : filename;
                boolean ok = writeToDownloads(data, name);
                final String msg = ok ? "تم حفظ الملف في التنزيلات: " + name
                        : "تعذر حفظ الملف — تحقق من أذونات التخزين";
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر حفظ الملف", Toast.LENGTH_LONG).show());
            }
        }
    }

    private boolean writeToDownloads(byte[] data, String filename) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, guessMime(filename));
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    out.write(data);
                }
                return true;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, filename);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String guessMime(String filename) {
        if (filename.endsWith(".csv")) return "text/csv";
        if (filename.endsWith(".json")) return "application/json";
        if (filename.endsWith(".xlsx"))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.addJavascriptInterface(new FileSaver(), "AndroidSaver");
        webView.addJavascriptInterface(new VoiceBridge(), "AndroidSpeech");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Helper the DownloadListener uses to pull a blob: URL out of the page.
                // Plus a Capacitor-compatible SpeechRecognition shim backed by the
                // native Android SpeechRecognizer (WebView has no Web Speech API).
                view.evaluateJavascript(
                    "(function(){"
                    + "window.__downloadBlob=function(url,filename){"
                    + "fetch(url).then(function(r){return r.arrayBuffer();}).then(function(buf){"
                    + "var bytes=new Uint8Array(buf);var bin='';var CH=0x8000;"
                    + "for(var i=0;i<bytes.length;i+=CH){bin+=String.fromCharCode.apply(null,bytes.subarray(i,i+CH));}"
                    + "AndroidSaver.saveBase64(btoa(bin),filename);"
                    + "}).catch(function(){/* download failed silently */});"
                    + "};"
                    + "window.__voiceResult=function(t){var r=window.__voiceResolve;window.__voiceResolve=null;if(r)r({matches:[t]});};"
                    + "window.__voiceError=function(){var r=window.__voiceResolve;window.__voiceResolve=null;if(r)r({matches:[]});};"
                    + "window.__sharedText=function(t){if(window.applySharedText)window.applySharedText(t);};"
                    + "window.Capacitor={Plugins:{SpeechRecognition:{"
                    + "requestPermissions:function(){return Promise.resolve();},"
                    + "start:function(opts){return new Promise(function(res,rej){window.__voiceResolve=res;window.__voiceReject=rej;AndroidSpeech.start();});}"
                    + "}}};"
                    + "})();", null);
                deliverSharedText();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Grant mic (and any other) permission requests from the page
                // so voice input can ask for the microphone.
                request.grant(request.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                // Let the user pick a backup JSON file for restore.
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Blob downloads (the app's Excel/CSV export) are not saved by WebView
        // on its own, so fetch the blob through the page and save it natively.
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                String filename = "finance-report.xlsx";
                if (mimetype != null && mimetype.contains("csv")) filename = "finance-report.csv";
                else if (mimetype != null && mimetype.contains("json")) {
                    // A JSON blob is a backup: never save it as finance-report.xlsx.
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(
                            "yyyy-MM-dd", java.util.Locale.US);
                    filename = "finance-backup-" + df.format(new java.util.Date()) + ".json";
                }
                else if (contentDisposition != null && contentDisposition.contains("filename=")) {
                    filename = contentDisposition.replaceAll(".*filename=\"?([^\";]+)\"?.*", "$1").trim();
                }
                final String fn = filename;
                final String u = url;
                if (u != null && u.startsWith("blob:")) {
                    webView.post(() -> webView.evaluateJavascript(
                            "window.__downloadBlob('" + u + "','" + fn + "')", null));
                } else {
                    Toast.makeText(MainActivity.this,
                            "التنزيل المباشر غير مدعوم في هذه النسخة", Toast.LENGTH_LONG).show();
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        webView.loadUrl("file:///android_asset/www/index.html");

        handleShareIntent(getIntent());
    }

    // ACTION_SEND (text/plain) from another app, e.g. sharing a Sham Cash
    // receipt. The text is queued and passed to the page once it loads;
    // the page shows it as a draft for the user to confirm, never saves it.
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (!"text/plain".equals(intent.getType())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        pendingSharedText = text;
        deliverSharedText();
    }

    private void deliverSharedText() {
        if (webView == null || pendingSharedText == null) return;
        try {
            String quoted = JSONObject.quote(pendingSharedText);
            pendingSharedText = null;
            webView.evaluateJavascript("window.__sharedText(" + quoted + ")", null);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
