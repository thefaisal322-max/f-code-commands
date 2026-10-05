package com.fainet.fcode;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole app is one screen: a WebView showing the editor UI from assets/www, plus a bridge
 * ("FcodeNative" in JavaScript) that connects the page to real shells and real files.
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/www/index.html";
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;

    /** Shell output waiting to be shown; a shell's reader thread pauses when this much is queued. */
    private static final int MAX_PENDING_OUTPUT = 256 * 1024;
    private static final long OUTPUT_FLUSH_DELAY_MS = 12;
    /** A Linux shell that ends with an error this soon after starting never really started. */
    private static final long LINUX_START_FAILURE_MS = 5000;

    private static final String FILES_AUTHORITY = "com.fainet.fcode.files";

    /**
     * A secret every shell gets in its environment (FCODE_TOKEN). The terminal's "open this"
     * request must carry it, so that text which merely passes through the terminal (a web page
     * fetched with curl, a file shown with cat) cannot make the app open links or files.
     */
    static final String SESSION_TOKEN = newToken();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, TerminalHost> terminals = new HashMap<>();   // guarded by "this"
    private volatile boolean destroyed;

    private WebView webView;
    private ProjectFiles projectFiles;
    private LinuxEnv linux;
    private LocalServer localServer;
    private File homeDir;
    private volatile Workspace workspace;
    private ValueCallback<Uri[]> fileChooserCallback;
    /** True while the user is answering Android's question about storage access. */
    private boolean storageSetupPending;
    /** The terminal that asked for storage access with setup-storage, or -1 when the page asked. */
    private int storageSetupTerminal = -1;

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
        workspace = Workspace.prepare(this, homeDir, hasStorageAccess());
        if (hasStorageAccess()) linkSharedFolders();
        projectFiles = new ProjectFiles(workspace.codes);   // the editor's files: Faisal/codes
        linux = new LinuxEnv(this);
        localServer = new LocalServer(workspace.codes);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0F1117);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // settings and the list of open tabs live in localStorage
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);         // lets the page read files picked with "Import File"
        settings.setSupportZoom(false);
        settings.setTextZoom(100);                    // keep the layout fitted whatever the system font size

        // The page is served from the APK's assets over https://appassets.androidplatform.net, a
        // normal secure origin (clipboard, storage) instead of file://. The editor's folder is
        // served under /codes/ so an HTML file can be previewed together with the files it links to.
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/codes/", this::serveCodesFile)
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
                openExternally(url);
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
        if (storageSetupPending && Build.VERSION.SDK_INT >= 30) storageAnswered();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) storageAnswered();
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
        List<TerminalHost> open;
        synchronized (this) {
            open = new ArrayList<>(terminals.values());
            terminals.clear();
        }
        for (TerminalHost host : open) host.close();
        KeepAliveService.stop(this);
        localServer.stop();
        mainHandler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }

    private void openExternally(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException ignored) {
            // No browser installed; nothing to open the link with.
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    /**
     * termux-open / xdg-open in a terminal: opens a link in the browser, or hands a file to the
     * app that handles its type (an .apk goes to Android's installer). Main thread only.
     *
     * @return null when it worked, otherwise what to tell the user
     */
    private String openFromTerminal(String target) {
        if (target == null || target.trim().isEmpty()) return "nothing to open";
        target = target.trim();

        // A link: only kinds that are safe to hand to whichever app claims them
        int colon = target.indexOf(':');
        if (!target.startsWith("/") && colon > 0) {
            String scheme = target.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")
                    && !scheme.equals("tel") && !scheme.equals("geo") && !scheme.equals("market")) {
                return "cannot open links that start with " + scheme + ":";
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                return null;
            } catch (ActivityNotFoundException e) {
                return "no app on this phone opens " + target;
            }
        }

        // A file. Paths inside the Linux system (/tmp, /root...) live under its folder on the phone.
        File file = new File(target);
        if (!file.exists()) file = new File(linux.rootDir(), target);
        if (!file.exists()) return target + ": no such file";
        if (file.isDirectory()) return target + " is a folder; give a file";

        final Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, FILES_AUTHORITY, file);
        } catch (IllegalArgumentException e) {
            return target + " is in a place other apps cannot be given";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = extension.equals("apk") ? "application/vnd.android.package-archive"
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (type == null) type = "*/*";

        // An .apk: Android wants the user's permission for Fcode to start installations, and it
        // ends the app the moment that permission changes (it has to re-attach the app's storage).
        // So ask for it on its own screen, with a warning, instead of losing the terminal by surprise.
        if (extension.equals("apk") && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return "to install apps from here, switch on \"Allow from this source\" on the screen that opened.\r\n"
                        + "Android restarts Fcode when you do. Then run the command again.";
            } catch (ActivityNotFoundException e) {
                // No such screen on this phone: the installer below asks in its own way.
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
            return null;
        } catch (ActivityNotFoundException e) {
            return "no app on this phone opens ." + extension + " files";
        } catch (SecurityException e) {
            return "Android did not allow opening " + target;
        }
    }

    private void runJs(String script) {
        if (!destroyed) webView.evaluateJavascript(script, null);
    }

    /* ---------- terminals ---------- */

    private synchronized TerminalHost terminal(int id) {
        return terminals.get(id);
    }

    /** Keeps the app alive in the background exactly while at least one shell is running. */
    private void updateKeepAlive() {
        boolean anyRunning = false;
        synchronized (this) {
            for (TerminalHost host : terminals.values()) {
                if (host.isRunning()) anyRunning = true;
            }
        }
        if (anyRunning) KeepAliveService.start(this);
        else KeepAliveService.stop(this);
    }

    /** One terminal on the page: its shell, and the shell's output on its way to the page. */
    private final class TerminalHost implements ShellSession.Listener {
        final int id;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private boolean flushScheduled;
        private volatile ShellSession session;
        private volatile boolean closed;
        private volatile boolean inLinux;
        private volatile long startedAt;
        private volatile int columns = 80;
        private volatile int rows = 24;

        TerminalHost(int id) {
            this.id = id;
        }

        boolean isRunning() {
            ShellSession current = session;
            return !closed && current != null && current.isRunning();
        }

        /** Runs on a background thread: setting up Linux the first time takes a few seconds. */
        void start(boolean wantLinux) {
            boolean useLinux = wantLinux;
            if (useLinux && !linux.isSupported()) {
                show("fcode: the Linux system is not available for this phone's processor.\r\nUsing Android's own shell.\r\n\r\n");
                useLinux = false;
            }
            if (useLinux && !linux.isInstalled()) {
                show("Setting up Linux (first time only)...\r\n");
                try {
                    linux.install();
                } catch (Throwable error) {
                    show("fcode: could not set up Linux: " + error + "\r\nUsing Android's own shell.\r\n\r\n");
                    useLinux = false;
                }
            }
            launch(useLinux);
        }

        private void launch(boolean useLinux) {
            if (closed) return;
            try {
                inLinux = useLinux;
                startedAt = SystemClock.elapsedRealtime();
                session = ShellSession.start(MainActivity.this, columns, rows, this, useLinux ? linux : null);
                mainHandler.post(MainActivity.this::updateKeepAlive);
            } catch (Throwable error) {
                session = null;
                if (useLinux) {
                    show("fcode: Linux could not start: " + error + "\r\nUsing Android's own shell.\r\n\r\n");
                    launch(false);
                } else {
                    show("\r\nfcode: could not start the shell: " + error + "\r\n");
                    notifyExit(-1);
                }
            }
        }

        void write(String data) {
            ShellSession current = session;
            if (current != null) current.write(data);
        }

        void resize(int newColumns, int newRows) {
            columns = newColumns;
            rows = newRows;
            ShellSession current = session;
            if (current != null) current.resize(newColumns, newRows);
        }

        void close() {
            closed = true;
            ShellSession current = session;
            if (current != null) current.destroy();
        }

        @Override
        public void onOutput(byte[] buffer, int length) {
            queue(buffer, length);
        }

        @Override
        public void onExit(int exitCode) {
            if (closed) return;
            boolean neverStarted = inLinux && (exitCode == LinuxEnv.EXIT_CANNOT_RUN
                    || (exitCode != 0 && SystemClock.elapsedRealtime() - startedAt < LINUX_START_FAILURE_MS));
            if (neverStarted) {
                // Whatever went wrong is printed just above this; keep the terminal usable anyway.
                show("\r\nfcode: Linux could not start (code " + exitCode + "). Using Android's own shell.\r\n"
                        + "You can pick the shell in Settings.\r\n\r\n");
                launch(false);
                return;
            }
            notifyExit(exitCode);
        }

        private void notifyExit(int exitCode) {
            mainHandler.post(() -> {
                flush();
                runJs("window.FcodeTerm && FcodeTerm.onExit(" + id + "," + exitCode + ")");
                updateKeepAlive();
            });
        }

        void show(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            queue(bytes, bytes.length);
        }

        /**
         * Collects output and hands it to the page in batches. A program that prints without
         * pause (for example `yes`) is slowed down here instead of flooding the page.
         */
        private void queue(byte[] buffer, int length) {
            synchronized (pending) {
                while (pending.size() > MAX_PENDING_OUTPUT && !closed && !destroyed) {
                    try {
                        pending.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                pending.write(buffer, 0, length);
                if (flushScheduled) return;
                flushScheduled = true;
            }
            mainHandler.postDelayed(this::flush, OUTPUT_FLUSH_DELAY_MS);
        }

        /** Main thread only. */
        private void flush() {
            byte[] data;
            synchronized (pending) {
                data = pending.toByteArray();
                pending.reset();
                flushScheduled = false;
                pending.notifyAll();
            }
            if (data.length == 0 || closed) return;
            // Base64 keeps the bytes intact whatever they are; the page decodes them for the terminal.
            runJs("window.FcodeTerm && FcodeTerm.onData(" + id + ",'" + Base64.encodeToString(data, Base64.NO_WRAP) + "')");
        }
    }

    /* ---------- phone storage: the Faisal folder, Downloads and friends ---------- */

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks Android for access to the phone's storage. Main thread only.
     *
     * @param terminal the terminal whose setup-storage command asked, or -1 when the page asked
     */
    private void beginStorageSetup(int terminal) {
        storageSetupTerminal = terminal;
        if (hasStorageAccess()) {
            storageAnswered();
            return;
        }
        storageSetupPending = true;
        if (Build.VERSION.SDK_INT >= 30) {
            reportStorage(terminal, "turn on \"Allow access to manage all files\" on the screen that opens, then come back.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException e2) {
                    storageSetupPending = false;
                    reportStorage(terminal, "this phone has no screen for allowing storage access.");
                    runJs("window.FcodeApp && FcodeApp.onStorageAnswer(false)");
                }
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_STORAGE_PERMISSION);
        }
    }

    /** The user has answered (or access was there already). Main thread only. */
    private void storageAnswered() {
        storageSetupPending = false;
        int terminal = storageSetupTerminal;
        storageSetupTerminal = -1;

        if (!hasStorageAccess()) {
            reportStorage(terminal, "storage access was not allowed. Run setup-storage to try again.");
            runJs("window.FcodeApp && FcodeApp.onStorageAnswer(false)");
            return;
        }
        boolean linked = linkSharedFolders();

        if (terminal >= 0) {
            // Asked from a running shell: do not move the folders under its feet.
            reportStorage(terminal, linked
                    ? "storage is ready. Your downloads are in ~/storage/downloads"
                    : "could not create the links in ~/storage.");
            if (!workspace.shared) {
                reportStorage(terminal, "your files move to the phone's " + Workspace.FOLDER + " folder the next time Fcode starts.");
            }
            return;
        }

        // Asked from the page: move codes and commands to the phone's storage now and start afresh.
        List<TerminalHost> open;
        synchronized (this) {
            open = new ArrayList<>(terminals.values());
            terminals.clear();
        }
        for (TerminalHost host : open) host.close();
        updateKeepAlive();
        workspace = Workspace.prepare(this, homeDir, true);
        runJs("window.FcodeApp && FcodeApp.onStorageAnswer(true)");
    }

    /** Creates ~/storage with links to the phone's shared folders, the way Termux lays them out. */
    private boolean linkSharedFolders() {
        File storage = new File(homeDir, "storage");
        if (!storage.isDirectory() && !storage.mkdirs()) return false;
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
        return made > 0;
    }

    /** Shows a line in a terminal, then gives its shell an empty line so it draws a fresh prompt. */
    private void reportStorage(int terminal, String message) {
        TerminalHost host = terminal(terminal);
        if (host == null) return;
        host.show("\r\nfcode: " + message + "\r\n");
        host.write("\n");
    }

    /* ---------- previews ---------- */

    /** Serves https://appassets.androidplatform.net/codes/... from the editor's folder. */
    private WebResourceResponse serveCodesFile(String path) {
        File file = LocalServer.resolve(workspace.codes, path);
        if (file != null && file.isDirectory()) file = new File(file, "index.html");
        if (file == null || !file.isFile()) return null;
        try {
            return new WebResourceResponse(LocalServer.contentType(file.getName()), null, new FileInputStream(file));
        } catch (IOException e) {
            return null;
        }
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
                info.put("linux", linux.isSupported());
                info.put("storageAccess", hasStorageAccess());
                info.put("sharedWorkspace", workspace.shared);
                info.put("workspaceName", Workspace.FOLDER);
            } catch (JSONException | PackageManager.NameNotFoundException ignored) {
                // Return whatever was collected.
            }
            return info.toString();
        }

        /**
         * Starts the shell for terminal {@code id}; if it is already running this only updates
         * the screen size. {@code mode} is "linux" or "android".
         */
        @JavascriptInterface
        public void startShell(int id, int columns, int rows, String mode) {
            final TerminalHost host;
            synchronized (MainActivity.this) {
                TerminalHost existing = terminals.get(id);
                if (existing != null && existing.isRunning()) {
                    existing.resize(columns, rows);
                    return;
                }
                host = new TerminalHost(id);
                terminals.put(id, host);
            }
            host.resize(columns, rows);
            final boolean wantLinux = !"android".equals(mode);
            Thread starter = new Thread(() -> host.start(wantLinux), "fcode-shell-start");
            starter.setDaemon(true);
            starter.start();
        }

        @JavascriptInterface
        public void write(int id, String data) {
            TerminalHost host = terminal(id);
            if (host != null) host.write(data);
        }

        @JavascriptInterface
        public void resize(int id, int columns, int rows) {
            TerminalHost host = terminal(id);
            if (host != null) host.resize(columns, rows);
        }

        @JavascriptInterface
        public void closeShell(int id) {
            TerminalHost host;
            synchronized (MainActivity.this) {
                host = terminals.remove(id);
            }
            if (host != null) host.close();
            mainHandler.post(MainActivity.this::updateKeepAlive);
        }

        /** The shell's setup-storage command ends up here. */
        @JavascriptInterface
        public void setupStorage(int id) {
            mainHandler.post(() -> beginStorageSetup(id));
        }

        /**
         * termux-open / xdg-open in a shell end up here. {@code token} proves the request comes
         * from a program in the shell; {@code target64} is the link or file path, Base64-encoded.
         */
        @JavascriptInterface
        public void openFromTerminal(int id, String token, String target64) {
            if (token == null || !SESSION_TOKEN.equals(token)) return;
            final String target;
            try {
                target = new String(Base64.decode(target64, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException | NullPointerException e) {
                return;
            }
            mainHandler.post(() -> {
                String problem = MainActivity.this.openFromTerminal(target);
                TerminalHost host = terminal(id);
                // No empty line is sent to the shell here: a script may be running and reading input
                if (problem != null && host != null) host.show("fcode: " + problem + "\r\n");
            });
        }

        /**
         * The page asks for the phone's storage so codes and commands can live in the Faisal
         * folder. The answer arrives in the page as FcodeApp.onStorageAnswer(true or false).
         */
        @JavascriptInterface
        public void requestStorage() {
            mainHandler.post(() -> beginStorageSetup(-1));
        }

        /** Makes the phone's status and navigation bars match the page's theme. */
        @JavascriptInterface
        public void setSystemBarColor(String cssColor) {
            final int color;
            try {
                color = Color.parseColor(cssColor);
            } catch (IllegalArgumentException | NullPointerException e) {
                return;
            }
            mainHandler.post(() -> {
                getWindow().setStatusBarColor(color);
                getWindow().setNavigationBarColor(color);
            });
        }

        /** Opens a file from the home folder in the phone's browser, served by a local web server. */
        @JavascriptInterface
        public boolean openInBrowser(String path) {
            try {
                int port = localServer.start();
                StringBuilder url = new StringBuilder("http://127.0.0.1:" + port);
                for (String part : (path == null ? "" : path).split("/")) {
                    if (!part.isEmpty()) url.append('/').append(Uri.encode(part));
                }
                final Uri uri = Uri.parse(url.toString());
                mainHandler.post(() -> openExternally(uri));
                return true;
            } catch (IOException e) {
                return false;
            }
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
