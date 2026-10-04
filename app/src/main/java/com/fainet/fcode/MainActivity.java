package com.fainet.fcode;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The whole app is one screen: a WebView showing the editor UI from assets/www, plus a bridge
 * ("FcodeNative" in JavaScript) that connects the Commands tab to a real shell.
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/www/index.html";
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;

    /** Shell output waiting to be shown; the reader thread pauses when this much is queued. */
    private static final int MAX_PENDING_OUTPUT = 256 * 1024;
    private static final long OUTPUT_FLUSH_DELAY_MS = 12;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream pendingOutput = new ByteArrayOutputStream();
    private boolean flushScheduled;
    private volatile boolean destroyed;

    private WebView webView;
    private ShellSession shell;
    private ProjectFiles projectFiles;
    private File homeDir;
    /** True while the user is away on Android's "All files access" settings screen. */
    private boolean storageSetupPending;
    private ValueCallback<Uri[]> fileChooserCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        try {
            homeDir = ShellSession.homeDir(this);
        } catch (IOException e) {
            homeDir = new File(getFilesDir(), "home");
        }
        projectFiles = new ProjectFiles(homeDir);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0F1117);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // the editor keeps files and settings in localStorage
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);         // lets the page read files picked with "Open File"
        settings.setSupportZoom(false);
        settings.setTextZoom(100);                    // keep the layout fitted whatever the system font size

        // Serve assets/www over https://appassets.androidplatform.net so the page runs in a
        // normal secure origin (clipboard, storage) instead of file://.
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!request.isForMainFrame() || APP_HOST.equals(url.getHost())) return false;
                // The bridge gives a page shell access, so only the app's own page may load here.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                    // No browser installed; nothing to open the link with.
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "FcodeNative");
        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER || fileChooserCallback == null) return;

        Uri[] picked = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                picked = new Uri[clip.getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(picked);
        fileChooserCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the "All files access" settings screen
        if (storageSetupPending && Build.VERSION.SDK_INT >= 30) {
            storageSetupPending = false;
            if (hasStorageAccess()) finishStorageSetup();
            else reportStorage("storage access was not allowed. Run setup-storage to try again.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) return;
        storageSetupPending = false;
        if (hasStorageAccess()) finishStorageSetup();
        else reportStorage("storage access was not allowed. Run setup-storage to try again.");
    }

    /** Back closes whatever panel is open in the page; with nothing open it leaves the app running. */
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.fcodeBack ? window.fcodeBack() : false", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        synchronized (this) {
            if (shell != null) shell.destroy();
            shell = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }

    /* ---------- shell output -> page ---------- */

    private final ShellSession.Listener shellListener = new ShellSession.Listener() {
        @Override
        public void onOutput(byte[] buffer, int length) {
            queueOutput(buffer, length);
        }

        @Override
        public void onExit(int exitCode) {
            mainHandler.post(() -> {
                flushOutput();
                if (destroyed) return;
                webView.evaluateJavascript("window.FcodeTerm && FcodeTerm.onExit(" + exitCode + ")", null);
            });
        }
    };

    /**
     * Collects output and hands it to the page in batches. A program that prints without pause
     * (for example `yes`) is slowed down here instead of flooding the page.
     */
    private void queueOutput(byte[] buffer, int length) {
        synchronized (pendingOutput) {
            while (pendingOutput.size() > MAX_PENDING_OUTPUT) {
                try {
                    pendingOutput.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            pendingOutput.write(buffer, 0, length);
            if (flushScheduled) return;
            flushScheduled = true;
        }
        mainHandler.postDelayed(this::flushOutput, OUTPUT_FLUSH_DELAY_MS);
    }

    private void flushOutput() {
        byte[] data;
        synchronized (pendingOutput) {
            data = pendingOutput.toByteArray();
            pendingOutput.reset();
            flushScheduled = false;
            pendingOutput.notifyAll();
        }
        if (data.length == 0 || destroyed) return;
        // Base64 keeps the bytes intact whatever they are; the page decodes them for the terminal.
        String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
        webView.evaluateJavascript("window.FcodeTerm && FcodeTerm.onData('" + encoded + "')", null);
    }

    /* ---------- phone storage (Downloads and friends) ---------- */

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs on the main thread when the user types setup-storage in the shell. */
    private void beginStorageSetup() {
        if (hasStorageAccess()) {
            finishStorageSetup();
            return;
        }
        storageSetupPending = true;
        if (Build.VERSION.SDK_INT >= 30) {
            reportStorage("turn on \"Allow access to manage all files\" on the screen that opens, then come back.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException e2) {
                    storageSetupPending = false;
                    reportStorage("this phone has no screen for allowing storage access.");
                }
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_STORAGE_PERMISSION);
        }
    }

    /** Creates ~/storage with links to the phone's shared folders, the way Termux lays them out. */
    private void finishStorageSetup() {
        File storage = new File(homeDir, "storage");
        if (!storage.isDirectory() && !storage.mkdirs()) {
            reportStorage("could not create ~/storage.");
            return;
        }
        String[][] links = {
                {"shared", null},
                {"downloads", Environment.DIRECTORY_DOWNLOADS},
                {"documents", Environment.DIRECTORY_DOCUMENTS},
                {"dcim", Environment.DIRECTORY_DCIM},
                {"pictures", Environment.DIRECTORY_PICTURES},
                {"music", Environment.DIRECTORY_MUSIC},
                {"movies", Environment.DIRECTORY_MOVIES},
        };
        int made = 0;
        for (String[] link : links) {
            File target = link[1] == null
                    ? Environment.getExternalStorageDirectory()
                    : Environment.getExternalStoragePublicDirectory(link[1]);
            File name = new File(storage, link[0]);
            name.delete();   // replaces a link left by an earlier run; a real non-empty folder is left alone
            try {
                Os.symlink(target.getAbsolutePath(), name.getAbsolutePath());
                made++;
            } catch (ErrnoException ignored) {
                // Something else already has this name; skip it.
            }
        }
        reportStorage(made > 0
                ? "storage is ready. Your downloads are in ~/storage/downloads"
                : "could not create the links in ~/storage.");
    }

    /** Shows a line in the terminal, then gives the shell an empty line so it draws a fresh prompt. */
    private void reportStorage(String message) {
        showInTerminal("\r\nfcode: " + message + "\r\n");
        ShellSession current;
        synchronized (this) {
            current = shell;
        }
        if (current != null) current.write("\n");
    }

    private void showInTerminal(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        queueOutput(bytes, bytes.length);
    }

    /* ---------- page -> app ---------- */

    /** Methods the page calls as FcodeNative.name(...). They run on a WebView background thread. */
    private final class Bridge {

        @JavascriptInterface
        public String info() {
            JSONObject info = new JSONObject();
            try {
                info.put("versionName", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                info.put("androidSdk", Build.VERSION.SDK_INT);
                info.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
                info.put("home", homeDir.getAbsolutePath());
            } catch (JSONException | android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Return whatever was collected.
            }
            return info.toString();
        }

        /** Starts the shell, or just updates the screen size if it is already running. */
        @JavascriptInterface
        public void startShell(int columns, int rows) {
            synchronized (MainActivity.this) {
                if (shell != null && shell.isRunning()) {
                    shell.resize(columns, rows);
                    return;
                }
                try {
                    shell = ShellSession.start(MainActivity.this, columns, rows, shellListener);
                } catch (Throwable error) {
                    shell = null;
                    showInTerminal("\r\nfcode: could not start the shell: " + error + "\r\n");
                    shellListener.onExit(-1);
                }
            }
        }

        @JavascriptInterface
        public void write(String data) {
            ShellSession current;
            synchronized (MainActivity.this) {
                current = shell;
            }
            if (current != null) current.write(data);
        }

        @JavascriptInterface
        public void resize(int columns, int rows) {
            ShellSession current;
            synchronized (MainActivity.this) {
                current = shell;
            }
            if (current != null) current.resize(columns, rows);
        }

        /** The shell's setup-storage command ends up here. */
        @JavascriptInterface
        public void setupStorage() {
            mainHandler.post(MainActivity.this::beginStorageSetup);
        }

        /* Files for the editor. Paths are relative to the shell's home folder; see ProjectFiles. */

        @JavascriptInterface
        public String fsList(String dir) {
            return projectFiles.list(dir);
        }

        @JavascriptInterface
        public String fsStat(String path) {
            return projectFiles.stat(path);
        }

        @JavascriptInterface
        public String fsRead(String path) {
            return projectFiles.read(path);
        }

        @JavascriptInterface
        public String fsWrite(String path, String content) {
            return projectFiles.write(path, content);
        }

        @JavascriptInterface
        public String fsWriteBase64(String path, String base64) {
            return projectFiles.writeBase64(path, base64);
        }

        @JavascriptInterface
        public String fsDelete(String path) {
            return projectFiles.delete(path);
        }

        @JavascriptInterface
        public String fsRename(String from, String to) {
            return projectFiles.rename(from, to);
        }

        @JavascriptInterface
        public String fsMkdir(String path) {
            return projectFiles.mkdir(path);
        }
    }
}
