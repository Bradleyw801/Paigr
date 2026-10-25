package com.textrelay.texts;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.URLUtil;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * The web page Texts was before it had screens of its own, shown full screen, for a phone still on the first version's
 * inbox: the page moves that inbox into a private one (web/app.js, moveIn), which only it knows how to do. Once the
 * move is done the page drops the first version's key and the app's own screens take over (see Bridge.setKey).
 * The page's own files come with the app (see bundled()): the code that holds the inbox key is the code this app was
 * built with, whatever the server sends.
 */
public class WebActivity extends Activity {
    static final String EXTRA_CONV = MainActivity.EXTRA_CONV;
    private static final int REQ_NOTIFICATIONS = 1;
    private static final int REQ_SCAN = 2;

    private WebView web;
    private Prefs prefs;
    private volatile boolean pageReady;
    /**
     * Whether the page showing is this app's own: set only when the app has just handed the WebView one of PAGES from its
     * assets for the page itself, and cleared by anything else that starts loading there. Only then does the bridge
     * answer. Nothing else ever loads in the app (see Client), so this is the second line, not the first.
     */
    private volatile boolean ownPage = false;
    private static boolean profileChosen;

    /**
     * The WebView keeps its storage, cookies and service workers in a folder of its own for this version of the page:
     * nothing an older Texts (which loaded its page from the server) left there, a service worker above all, can serve
     * the page or stand in for it. Must run before anything else in the process touches WebView.
     */
    static synchronized void useOwnWebViewProfile() {
        if (profileChosen) return;
        profileChosen = true;
        try {
            WebView.setDataDirectorySuffix("own");
        } catch (RuntimeException | LinkageError ignored) {
            // already set up in this process (the app's own start does it first)
        }
    }
    private String pendingConv;
    private boolean askedForNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        useOwnWebViewProfile();
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " TextsApp/" + MainActivity.VERSION);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "TextsNative");
        web.setDownloadListener((url, userAgent, contentDisposition, mime, length) -> download(url, contentDisposition, mime));
        try {
            // A service worker left by an older version never gets to serve the page, or any other page, from the server.
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    WebResourceResponse own = bundled(request.getUrl(), request.getMethod());
                    if (own != null) return own;
                    return sameOrigin(Uri.parse(base()), request.getUrl()) && !isApi(request.getUrl()) ? empty(404) : null;
                }
            });
        } catch (RuntimeException ignored) {
            // no service worker support in this WebView
        }

        checkSamePhone();   // a link copied here from another phone goes before anything uses it
        new Thread(this::forgetOldWebViewProfile, "texts-old-profile").start();
        pendingConv = getIntent() != null ? getIntent().getStringExtra(EXTRA_CONV) : null;
        load(pendingConv);
    }

    // ------------------------------------------------------------------ the page's own files

    /** Path on the inbox server -> file in assets/web. */
    static final java.util.Map<String, String> BUNDLED = new java.util.HashMap<>();
    static {
        BUNDLED.put("/", "index.html");
        for (String f : new String[] { "index.html", "app.js", "tr2.js", "styles.css", "manifest.webmanifest", "sw.js",
                "icons/texts-192.png", "icons/texts-512.png", "icons/texts-maskable-512.png", "fonts/Figtree.woff2" }) BUNDLED.put("/" + f, f);
    }

    /** The pages among them: what may show in the app, and the only ones the bridge answers. */
    static final java.util.Set<String> PAGES = new java.util.HashSet<>(java.util.Arrays.asList("/", "/index.html"));

    static boolean sameOrigin(Uri home, Uri u) {
        return u != null && home != null && home.getHost() != null && home.getHost().equalsIgnoreCase(u.getHost())
                && home.getPort() == u.getPort() && String.valueOf(home.getScheme()).equalsIgnoreCase(u.getScheme());
    }

    private static String pathOf(Uri u) {
        return u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
    }

    /** This app's own page on the inbox server: "/" or "/index.html", whatever its query or fragment. */
    static boolean isOwnPage(Uri home, Uri u) {
        return sameOrigin(home, u) && PAGES.contains(pathOf(u));
    }

    /** The inbox's API, files and realtime: what the page fetches, never a page itself. */
    static boolean isApi(Uri u) {
        String p = pathOf(u);
        return p.startsWith("/v2/") || p.startsWith("/api/") || p.startsWith("/__hedy/") || p.startsWith("/media/");
    }

    private static WebResourceResponse empty(int status) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", status, status == 404 ? "Not Found" : "OK", headers,
                new java.io.ByteArrayInputStream("<!doctype html><meta charset=utf-8><title>Paigr</title>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** The bundled copy of one of the page's files, when the request is for one on the inbox server; else null. */
    WebResourceResponse bundled(Uri u, String method) {
        if (u == null || (method != null && !"GET".equalsIgnoreCase(method))) return null;
        if (!sameOrigin(Uri.parse(base()), u)) return null;
        String file = BUNDLED.get(pathOf(u));
        if (file == null) return null;
        try {
            java.io.InputStream in = getAssets().open("web/" + file);
            String mime = file.endsWith(".html") ? "text/html" : file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css"
                    : file.endsWith(".png") ? "image/png" : file.endsWith(".webmanifest") ? "application/manifest+json"
                    : file.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") ? "utf-8" : null, 200, "OK", headers, in);
        } catch (IOException e) {
            return empty(500);   // never the server's copy in its place
        }
    }

    // ------------------------------------------------------------------ the private inbox

    /**
     * A copy of the app's data can reach another phone (backups leave it out, but a rooted phone can copy anything). A
     * link that came along isn't this phone's (it was stored under the Android ID Texts had on the old one): Prefs drops
     * it wherever it's read, and this phone links with a code. Here the app also stops what the link had running.
     */
    private void checkSamePhone() {
        if (prefs.link() != null || !prefs.takeLinkCopied()) return;
        SyncService.stop(this);
        SyncService.notifier(this).clearAll();
    }

    /** The folder older versions' WebView used (see useOwnWebViewProfile): never used again, so it goes. */
    private void forgetOldWebViewProfile() {
        java.io.File old = new java.io.File(getApplicationInfo().dataDir, "app_webview");
        if (old.isDirectory()) deleteTree(old);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private String base() {
        return prefs.server();
    }

    private void load(String conv) {
        String url = base() + "/";
        if (conv != null && !conv.isEmpty()) url += "?conv=" + encode(conv);
        pageReady = false;
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String conv = intent != null ? intent.getStringExtra(EXTRA_CONV) : null;
        if (conv == null || conv.isEmpty()) return;
        if (pageReady) {
            web.evaluateJavascript("window.textsOpen && textsOpen(" + JSONObject.quote(conv) + ")", null);
        } else {
            pendingConv = conv;
            load(conv);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        SyncService.setScreenOpen(this, true);
        if (prefs.paired()) {
            SyncService.start(this);
            SyncService.wake();
        }
        if (!askedForNotifications && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedForNotifications = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onPause() {
        SyncService.setScreenOpen(this, false);
        web.onPause();
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")   // the web app owns navigation; this just asks it first
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.textsBack && textsBack()) ? 'true' : 'false'", value -> {
            if ("\"true\"".equals(value)) return;
            if (web.canGoBack()) web.goBack();
            else moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    /** The scanner closed: hand the page what it read, or tell it the scanner closed (and whether to type instead). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN) return;
        String text = resultCode == RESULT_OK && data != null ? data.getStringExtra(ScanActivity.EXTRA_TEXT) : null;
        if (text != null) web.evaluateJavascript("window.textsScanned && textsScanned(" + JSONObject.quote(text) + ")", null);
        else web.evaluateJavascript("window.textsScanClosed && textsScanClosed(" + (resultCode == ScanActivity.RESULT_TYPE) + ")", null);
    }

    /** Android's own setting for the background notification, where it can be turned off. */
    private void openStatusSettings() {
        SyncService.createChannels(this);
        Intent channel = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, SyncService.CHANNEL);
        try {
            startActivity(channel);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void download(String url, String contentDisposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mime);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            getSystemService(DownloadManager.class).enqueue(r);
            Toast.makeText(this, "Downloading " + name + ". Tap it in your notifications when it finishes.", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * Keeps this app's own page inside the app and sends everything else to the phone's browser or dialer: a link in
     * a text, even one to another page on the inbox server, never opens where the bridge (and the inbox key) is.
     */
    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (request.isForMainFrame() && isOwnPage(Uri.parse(base()), u)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(WebActivity.this, "Nothing on this phone can open that link.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()) {
                // Decided before the page arrives, so no other page's script ever runs while the bridge would answer it.
                ownPage = false;
                boolean page = isOwnPage(Uri.parse(base()), request.getUrl()) && (request.getMethod() == null || "GET".equalsIgnoreCase(request.getMethod()));
                if (!page) return empty(200);   // anything else that tries to load here (a form, a redirect) shows nothing
                WebResourceResponse own = bundled(request.getUrl(), request.getMethod());
                ownPage = own != null && own.getStatusCode() == 200;
                return own != null ? own : empty(500);
            }
            WebResourceResponse own = bundled(request.getUrl(), request.getMethod());
            return own != null ? own : super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (url == null || !isOwnPage(Uri.parse(base()), Uri.parse(url))) ownPage = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            if ("done".equals(MainActivity.recovery)) view.evaluateJavascript("window.textsLinked && textsLinked()", null);
            if (pendingConv != null) {
                String conv = pendingConv;
                pendingConv = null;
                view.evaluateJavascript("window.textsOpen && textsOpen(" + JSONObject.quote(conv) + ")", null);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<meta name=color-scheme content='light dark'>"
                    + "<body style='font-family:Roboto,system-ui,sans-serif;padding:40px 24px;text-align:center'>"
                    + "<h2 style='margin:0 0 8px;font-weight:500'>Can’t reach the inbox</h2>"
                    + "<p style='color:#6f7471'>Check Wi-Fi or mobile data on this phone, then try again.</p>"
                    + "<p><button onclick=\"location.replace('" + base() + "/')\" style='font:inherit;font-weight:500;padding:10px 24px;border:0;border-radius:20px;background:#0b57d0;color:#fff'>Try again</button></p>";
            view.loadDataWithBaseURL(base() + "/", html, "text/html", "utf-8", null);
        }
    }

    /**
     * What the web app can ask the phone for. Methods run on a background thread, and answer only this app's own page
     * (see ownPage): any other page gets nothing.
     */
    private final class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return ownPage;
        }

        @JavascriptInterface
        public String version() {
            if (!ownPage) return "";
            return MainActivity.VERSION;
        }

        @JavascriptInterface
        public String key() {
            if (!ownPage) return "";
            return prefs.key();
        }

        /** This phone's private inbox, { tid, token, key }, or "" when it has none. */
        @JavascriptInterface
        public String link() {
            if (!ownPage) return "";
            Link l = prefs.link();
            return l != null ? l.forPage() : "";
        }

        /** The page linked this phone to a private inbox ("" when it unlinked it); the background checks follow. */
        @JavascriptInterface
        public void setLink(String json) {
            if (!ownPage) return;
            Link l = Link.parse(json, prefs.server());
            prefs.setLink(l);
            runOnUiThread(() -> {
                SyncService.stop(WebActivity.this);   // a fresh start, on the new inbox's channel
                if (prefs.paired()) {
                    SyncService.start(WebActivity.this);
                } else {
                    SyncService.notifier(WebActivity.this).clearAll();
                }
            });
            if (l != null) new Thread(() -> MainActivity.fileWrap(WebActivity.this, prefs, l), "texts-wrap").start();
        }

        /** "pending" while asking the inbox whether this phone was linked before; "done" otherwise. */
        @JavascriptInterface
        public String recovery() {
            if (!ownPage) return "";
            return MainActivity.recovery;
        }

        /** What this device is called in the inbox's list of devices. */
        @JavascriptInterface
        public String deviceName() {
            if (!ownPage) return "";
            return Inbox.deviceLabel();
        }

        /** Saves an opened attachment to Downloads and opens it. Returns what to tell the person. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            if (!ownPage) return "";
            try {
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "Couldn't save it to Downloads.";
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "Couldn't save it to Downloads.";
                    out.write(bytes);
                }
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        startActivity(view);
                    } catch (ActivityNotFoundException ignored) {
                        // saved; nothing here opens it
                    }
                });
                return "Saved to Downloads as " + name + ".";
            } catch (IOException | RuntimeException e) {
                return "Couldn't save it: " + e.getMessage();
            }
        }

        /** The page linked (or unlinked) this phone; the background checks follow. */
        @JavascriptInterface
        public void setKey(String key) {
            if (!ownPage) return;
            prefs.setKey(key);
            runOnUiThread(() -> {
                if (prefs.paired()) {
                    SyncService.start(WebActivity.this);
                } else {
                    SyncService.stop(WebActivity.this);
                    SyncService.notifier(WebActivity.this).clearAll();
                }
                // Moved (or unlinked): nothing is left that only the page can do, so the app's own screens take over.
                if (prefs.key().isEmpty()) {
                    startActivity(new Intent(WebActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void openedConversation(String conv) {
            if (!ownPage) return;
            SyncService.notifier(WebActivity.this).clear(conv);
        }

        @JavascriptInterface
        public boolean notificationsAllowed() {
            if (!ownPage) return false;
            return getSystemService(NotificationManager.class).areNotificationsEnabled();
        }

        @JavascriptInterface
        public void requestNotifications() {
            if (!ownPage) return;
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                } else {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
            });
        }

        /** Opens the built-in scanner; what it reads comes back through textsScanned(). */
        @JavascriptInterface
        public void scanQr() {
            if (!ownPage) return;
            runOnUiThread(() -> startActivityForResult(new Intent(WebActivity.this, ScanActivity.class), REQ_SCAN));
        }

        /** How new messages alert: "normal" (sound, vibration, pop-up), "quiet" (in the shade only) or "off" (not at all). */
        @JavascriptInterface
        public String alertMode() {
            if (!ownPage) return "";
            return prefs.alertMode();
        }

        /** With "off", nothing runs in the background, so there is no background notification either. */
        @JavascriptInterface
        public void setAlertMode(String mode) {
            if (!ownPage) return;
            prefs.setAlertMode(mode);
            runOnUiThread(() -> {
                if (Prefs.ALERT_OFF.equals(prefs.alertMode())) {
                    SyncService.stop(WebActivity.this);
                    SyncService.notifier(WebActivity.this).clearAll();
                } else if (prefs.paired()) {
                    SyncService.start(WebActivity.this);
                }
            });
        }

        /** The background notification: "hidden" once it's turned off in Android's settings, else "shown". */
        @JavascriptInterface
        public String statusMode() {
            if (!ownPage) return "";
            return SyncService.statusHidden(WebActivity.this) ? "hidden" : "shown";
        }

        /** Only Android's settings can turn it off or on again for an app that listens in the background: opens them. */
        @JavascriptInterface
        public void setStatusMode(String mode) {
            if (!ownPage) return;
            boolean hide = "hidden".equals(mode);
            if (hide != SyncService.statusHidden(WebActivity.this)) runOnUiThread(WebActivity.this::openStatusSettings);
        }

        @JavascriptInterface
        public boolean batteryExempt() {
            if (!ownPage) return false;
            return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void requestBattery() {
            if (!ownPage) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }
    }
}
