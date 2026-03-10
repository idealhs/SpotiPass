package com.spotipass.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class SpotiPass {

    private static final String TAG = "SpotiPass";

    private static volatile String TARGET_PACKAGE = "";
    private static final String CHALLENGE_HOST = "challenge.spotify.com";
    private static final String RECAPTCHA_GOOGLE_HOST = "www.google.com";
    private static final String RECAPTCHA_NET_HOST = "www.recaptcha.net";
    private static final String GSTATIC_CN_HOST = "www.gstatic.cn";
    private static final String RECAPTCHA_PATH_PREFIX = "/recaptcha/";
    private static final String CHALLENGE_LAUNCHER_ACTIVITY = "com.spotify.login.adaptiveauthentication.challenge.web.NoAnimLauncherActivity";
    private static final String TWA_FALLBACK_LAUNCH_URL_EXTRA = "com.google.browser.examples.twawebviewfallback.WebViewFallbackActivity.LAUNCH_URL";
    private static final String[] WEBVIEW_LOGIN_PROXY_REVERSE_BYPASS_RULES = new String[]{
            "*.spotify.com",
            CHALLENGE_HOST,
            RECAPTCHA_GOOGLE_HOST,
            RECAPTCHA_NET_HOST,
            "www.gstatic.com",
            GSTATIC_CN_HOST
    };

    private static volatile boolean installed;

    private static final AtomicBoolean ensuringCore = new AtomicBoolean(false);
    private static final AtomicBoolean configLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean firstLaunchDialogShown = new AtomicBoolean(false);
    private static volatile WeakReference<Activity> lastTargetActivity = new WeakReference<>(null);
    private static volatile WeakReference<Activity> lastStableTargetActivity = new WeakReference<>(null);
    private static volatile SpotiPassPrefs.Config cachedConfig = new SpotiPassPrefs.Config(
            false,
            SpotiPassKeys.LOGIN_MODE_NONE,
            "",
            "",
            "",
            false,
            "",
            ""
    );

    private static final Set<String> loggedDnsHosts = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedLoginProxyKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedRecaptchaRewritePaths = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedViewIntentKeys = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean challengeDialogShowing = new AtomicBoolean(false);
    private static final AtomicReference<String> pendingChallengeUrl = new AtomicReference<>(null);
    private static volatile String cachedLoginDnsRulesRaw = "";
    private static volatile Map<String, List<byte[]>> cachedLoginDnsRules = Collections.emptyMap();
    private static final ThreadLocal<Boolean> recaptchaRewriteGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> challengeIntentGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Object webViewLocalProxyLock = new Object();
    private static volatile Closeable activeWebViewLocalProxyHandle;

    private SpotiPass() {}

    private static final class LoginHttpProxyConfig {
        final String host;
        final int port;
        final boolean useTlsToProxy;
        final String username;
        final String password;
        final Proxy proxy;

        LoginHttpProxyConfig(String host, int port, boolean useTlsToProxy, String username, String password) {
            this.host = host;
            this.port = port;
            this.useTlsToProxy = useTlsToProxy;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        }

        boolean hasCredentials() {
            return !username.isEmpty() || !password.isEmpty();
        }

        String target() {
            return host + ":" + port;
        }

        String schemeLabel() {
            return useTlsToProxy ? "https://" : "http://";
        }

        String displayTarget() {
            return schemeLabel() + target();
        }

        String proxyAuthorizationHeader() {
            String userPass = username + ":" + password;
            String encoded = Base64.encodeToString(
                    userPass.getBytes(StandardCharsets.ISO_8859_1),
                    Base64.NO_WRAP
            );
            return "Basic " + encoded;
        }
    }

    private static final class WebViewProxyOverrideState {
        final ProxyConfig proxyConfig;
        final String displayTarget;
        final Closeable localProxyHandle;

        WebViewProxyOverrideState(ProxyConfig proxyConfig, String displayTarget, Closeable localProxyHandle) {
            this.proxyConfig = proxyConfig;
            this.displayTarget = displayTarget;
            this.localProxyHandle = localProxyHandle;
        }
    }

    private static final class LoginProxyTlsSocketFactory extends SocketFactory {
        private final SocketFactory delegate;

        LoginProxyTlsSocketFactory(SocketFactory delegate) {
            this.delegate = delegate == null ? SocketFactory.getDefault() : delegate;
        }

        @Override
        public Socket createSocket() throws IOException {
            return new LoginProxyTlsSocket(delegate);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }

    private static final class LoginProxyTlsSocket extends Socket {
        private final SocketFactory delegateFactory;
        private Socket delegateSocket;

        LoginProxyTlsSocket(SocketFactory delegateFactory) throws IOException {
            this.delegateFactory = delegateFactory == null ? SocketFactory.getDefault() : delegateFactory;
            this.delegateSocket = this.delegateFactory.createSocket();
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public synchronized void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (delegateSocket == null) {
                delegateSocket = delegateFactory.createSocket();
            }

            LoginHttpProxyConfig config = getActiveLoginProxyConfig();
            if (config == null || !config.useTlsToProxy || !isProxyEndpoint(endpoint, config)) {
                delegateSocket.connect(endpoint, timeout);
                return;
            }

            Socket rawSocket = delegateSocket;
            try {
                rawSocket.connect(endpoint, timeout);
                delegateSocket = wrapProxySocketWithTls(rawSocket, config);
                logLoginProxyRuntimeOnce(
                        "proxyTls|" + config.target(),
                        "已与登录代理 " + config.displayTarget() + " 建立 TLS 连接",
                        "established TLS connection to login proxy " + config.displayTarget()
                );
            } catch (IOException e) {
                try {
                    rawSocket.close();
                } catch (Throwable ignored) {
                }
                throw e;
            }
        }

        @Override
        public void bind(SocketAddress bindpoint) throws IOException {
            delegateSocket.bind(bindpoint);
        }

        @Override
        public InetAddress getInetAddress() {
            return delegateSocket.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return delegateSocket.getLocalAddress();
        }

        @Override
        public int getPort() {
            return delegateSocket.getPort();
        }

        @Override
        public int getLocalPort() {
            return delegateSocket.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegateSocket.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegateSocket.getLocalSocketAddress();
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            return delegateSocket.getInputStream();
        }

        @Override
        public java.io.OutputStream getOutputStream() throws IOException {
            return delegateSocket.getOutputStream();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            delegateSocket.setTcpNoDelay(on);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return delegateSocket.getTcpNoDelay();
        }

        @Override
        public void setSoLinger(boolean on, int linger) throws SocketException {
            delegateSocket.setSoLinger(on, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return delegateSocket.getSoLinger();
        }

        @Override
        public void sendUrgentData(int data) throws IOException {
            delegateSocket.sendUrgentData(data);
        }

        @Override
        public void setOOBInline(boolean on) throws SocketException {
            delegateSocket.setOOBInline(on);
        }

        @Override
        public boolean getOOBInline() throws SocketException {
            return delegateSocket.getOOBInline();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            delegateSocket.setSoTimeout(timeout);
        }

        @Override
        public synchronized int getSoTimeout() throws SocketException {
            return delegateSocket.getSoTimeout();
        }

        @Override
        public synchronized void setSendBufferSize(int size) throws SocketException {
            delegateSocket.setSendBufferSize(size);
        }

        @Override
        public synchronized int getSendBufferSize() throws SocketException {
            return delegateSocket.getSendBufferSize();
        }

        @Override
        public synchronized void setReceiveBufferSize(int size) throws SocketException {
            delegateSocket.setReceiveBufferSize(size);
        }

        @Override
        public synchronized int getReceiveBufferSize() throws SocketException {
            return delegateSocket.getReceiveBufferSize();
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            delegateSocket.setKeepAlive(on);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return delegateSocket.getKeepAlive();
        }

        @Override
        public void setTrafficClass(int tc) throws SocketException {
            delegateSocket.setTrafficClass(tc);
        }

        @Override
        public int getTrafficClass() throws SocketException {
            return delegateSocket.getTrafficClass();
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            delegateSocket.setReuseAddress(on);
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return delegateSocket.getReuseAddress();
        }

        @Override
        public synchronized void close() throws IOException {
            delegateSocket.close();
        }

        @Override
        public void shutdownInput() throws IOException {
            delegateSocket.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            delegateSocket.shutdownOutput();
        }

        @Override
        public String toString() {
            return delegateSocket.toString();
        }

        @Override
        public boolean isConnected() {
            return delegateSocket.isConnected();
        }

        @Override
        public boolean isBound() {
            return delegateSocket.isBound();
        }

        @Override
        public boolean isClosed() {
            return delegateSocket.isClosed();
        }

        @Override
        public boolean isInputShutdown() {
            return delegateSocket.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegateSocket.isOutputShutdown();
        }

        @Override
        public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            delegateSocket.setPerformancePreferences(connectionTime, latency, bandwidth);
        }
    }

    private static final class LoginProxySelector extends ProxySelector {
        private final ProxySelector delegate;

        LoginProxySelector(ProxySelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) {
                return delegateSelect(uri);
            }
            String host = normalizeHost(uri.getHost());
            LoginHttpProxyConfig config = getActiveLoginProxyConfig();
            if (config != null && isSpotifyLoginProxyHost(host)) {
                logRuntime(
                        "OkHttp 登录请求 " + uri + " 通过代理 " + config.displayTarget(),
                        "OkHttp login request " + uri + " via proxy " + config.displayTarget()
                );
                return Collections.singletonList(config.proxy);
            }
            return delegateSelect(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            String host = uri == null ? "" : normalizeHost(uri.getHost());
            if (isSpotifyLoginProxyHost(host)) {
                String reason = ioe == null ? "" : ioe.getClass().getSimpleName() + ":" + ioe.getMessage();
                logLoginProxyOnce("connectFailed|" + host + "|" + reason,
                        "login proxy connect failed for " + host + ": " + reason);
            }
            if (delegate != null) {
                try {
                    delegate.connectFailed(uri, sa, ioe);
                } catch (Throwable ignored) {
                }
            }
        }

        private List<Proxy> delegateSelect(URI uri) {
            if (delegate != null) {
                try {
                    List<Proxy> selected = delegate.select(uri);
                    if (selected != null && !selected.isEmpty()) {
                        return selected;
                    }
                } catch (Throwable ignored) {
                }
            }
            return Collections.singletonList(Proxy.NO_PROXY);
        }
    }

    private static final class LoginProxyAuthenticatorHandler implements InvocationHandler {
        private final Object delegate;

        LoginProxyAuthenticatorHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name) && (args == null || args.length == 0)) {
                return "LoginProxyAuthenticator(" + delegate + ")";
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if (!"authenticate".equals(name) || args == null || args.length < 2) {
                return invokeDelegate(method, args);
            }

            Object response = args[1];
            String host = extractRequestHostFromResponse(response);
            if (!isSpotifyLoginProxyHost(host)) {
                return invokeDelegate(method, args);
            }

            LoginHttpProxyConfig config = getActiveLoginProxyConfig();
            if (config == null || !config.hasCredentials()) {
                return invokeDelegate(method, args);
            }

            int responseCode = extractResponseCode(response);
            if (responseCode != 407) {
                return invokeDelegate(method, args);
            }

            Object request = getRequestFromResponse(response);
            if (request == null) {
                return invokeDelegate(method, args);
            }

            String existingHeader = getRequestHeader(request, "Proxy-Authorization");
            if (existingHeader != null && !existingHeader.isEmpty()) {
                return null;
            }

            try {
                Object requestBuilder = XposedHelpers.callMethod(request, "newBuilder");
                XposedHelpers.callMethod(
                        requestBuilder,
                        "header",
                        "Proxy-Authorization",
                        config.proxyAuthorizationHeader()
                );
                logLoginProxyRuntimeOnce(
                        "auth|" + host + "|" + config.target(),
                        "已为 " + host + " 配置代理认证，经由 " + config.displayTarget(),
                        "configured proxy authentication for " + host + " via " + config.displayTarget()
                );
                return XposedHelpers.callMethod(requestBuilder, "build");
            } catch (Throwable t) {
                logLoginProxyOnce("authFailed|" + host + "|" + t.getClass().getName(),
                        "login proxy authenticator failed for " + host + ": " + t);
                return invokeDelegate(method, args);
            }
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            if (delegate == null) return null;
            return method.invoke(delegate, args);
        }
    }

    private static void log(String message) {
        String line = TAG + ": " + message;
        XposedBridge.log(line);
        SpotiPassRuntimeLog.append(line);
    }

    private static void logRuntime(String zhHans, String english) {
        String message = SpotiPassI18n.text(zhHans, english);
        String line = TAG + ": " + message;
        XposedBridge.log(line);
        SpotiPassRuntimeLog.append(line);
    }

    public static void install(ClassLoader appClassLoader, String packageName) {
        if (installed) return;
        installed = true;
        TARGET_PACKAGE = packageName;

        log("installing for package: " + packageName);

        hookActivityResume();
        hookChallengeFlowIntent(appClassLoader);
        hookContextChallengeFlowIntent();
        hookContextImplChallengeFlowIntent();
        hookInstrumentationChallengeFlow();
        hookCustomTabsChallengeFlow(appClassLoader);
        hookChallengeLauncherFlow(appClassLoader);
        hookChallengeBridgeLaunch(appClassLoader);
        hookUrlOpenConnection();
        hookRecaptchaUrlRewrite(appClassLoader);
        hookLoginDns(appClassLoader);
        hookLoginProxy(appClassLoader);
    }

    private static void hookActivityResume() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET_PACKAGE.equals(activity.getPackageName())) return;
                    lastTargetActivity = new WeakReference<>(activity);
                    boolean challengeLauncher = isChallengeLauncherActivity(activity);
                    if (!challengeLauncher) {
                        lastStableTargetActivity = new WeakReference<>(activity);
                    }

                    Context appContext = activity.getApplicationContext();

                    SpotiPassPrefs prefsInstance = SpotiPassPrefs.getInstance(appContext);
                    prefsInstance.setOnConfigChangeListener(() -> {
                        configLoaded.set(false);
                        ensureCoreStateAsync(appContext);
                    });

                    SpotiPassPrefs.Config currentConfig = getCachedConfig(appContext);
                    if (!challengeLauncher) {
                        replayPendingChallengeIfNeeded(activity, currentConfig);
                    }

                    Runnable showDialog = () -> SpotiPassConfigDialog.show(activity, () -> {
                        configLoaded.set(false);
                        ensureCoreStateAsync(appContext);
                    });
                    if (!challengeLauncher) {
                        SpotiPassFloatingButton.inject(activity, showDialog);
                    }

                    if (!challengeLauncher
                            && prefsInstance.isFirstLaunch()
                            && firstLaunchDialogShown.compareAndSet(false, true)) {
                        activity.getWindow().getDecorView().postDelayed(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                firstLaunchDialogShown.set(false);
                                return;
                            }
                            SpotiPassConfigDialog.show(activity, () -> {
                                configLoaded.set(false);
                                ensureCoreStateAsync(appContext);
                            });
                        }, 1500);
                    }

                    ensureCoreStateAsync(appContext);
                }
            });
        } catch (Throwable t) {
            log("hookActivityResume failed: " + t);
        }
    }

    private static void hookChallengeFlowIntent(ClassLoader appClassLoader) {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int intentArgIndex = findIntentArgIndex(param.args);
                    if (intentArgIndex < 0) return;
                    interceptChallengeLaunch(param, intentArgIndex);
                }
            };
            int hooked = 0;
            hooked += XposedBridge.hookAllMethods(Activity.class, "startActivity", hook).size();
            hooked += XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", hook).size();
            hooked += XposedBridge.hookAllMethods(Activity.class, "startActivityIfNeeded", hook).size();
            hooked += XposedBridge.hookAllMethods(Activity.class, "startNextMatchingActivity", hook).size();
            log("hook Activity start* methods: " + hooked);
        } catch (Throwable t) {
            log("hookChallengeFlowIntent failed: " + t);
        }
    }

    private static void hookContextChallengeFlowIntent() {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int intentArgIndex = findIntentArgIndex(param.args);
                    if (intentArgIndex < 0) return;
                    interceptChallengeLaunchFromContext(param, intentArgIndex);
                }
            };
            int hooked = 0;
            hooked += XposedBridge.hookAllMethods(android.content.ContextWrapper.class, "startActivity", hook).size();
            log("hook ContextWrapper.startActivity methods: " + hooked);
        } catch (Throwable t) {
            log("hookContextChallengeFlowIntent failed: " + t);
        }
    }

    private static void hookContextImplChallengeFlowIntent() {
        try {
            Class<?> contextImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", null);
            if (contextImpl == null) return;
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int intentArgIndex = findIntentArgIndex(param.args);
                    if (intentArgIndex < 0) return;
                    interceptChallengeLaunchFromContext(param, intentArgIndex);
                }
            };
            int hooked = XposedBridge.hookAllMethods(contextImpl, "startActivity", hook).size();
            log("hook ContextImpl.startActivity methods: " + hooked);
        } catch (Throwable t) {
            log("hookContextImplChallengeFlowIntent failed: " + t);
        }
    }

    private static void hookInstrumentationChallengeFlow() {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
                    int intentArgIndex = findIntentArgIndex(param.args);
                    if (intentArgIndex < 0) return;
                    Object arg = param.args[intentArgIndex];
                    if (!(arg instanceof Intent)) return;
                    Intent intent = (Intent) arg;

                    Activity activity = null;
                    Context context = null;
                    if (param.args != null) {
                        for (Object value : param.args) {
                            if (activity == null && value instanceof Activity) {
                                activity = (Activity) value;
                            }
                            if (context == null && value instanceof Context) {
                                context = (Context) value;
                            }
                        }
                    }
                    if (context == null) context = activity;
                    if (context == null) return;
                    if (!TARGET_PACKAGE.equals(context.getPackageName())) return;

                    Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
                    SpotiPassPrefs.Config config = getCachedConfig(appContext);
                    if (!config.enabled) return;

                    maybeLogViewIntent("instr", intent);
                    String viewUrl = extractViewUrl(intent);
                    if (viewUrl == null || viewUrl.isEmpty()) return;
                    if (!shouldHandleLoginFlowIntent(intent, viewUrl)) return;

                    Activity targetActivity = resolveChallengeHostActivity(context);
                    if (targetActivity == null) {
                        cachePendingChallenge(viewUrl, "instrumentation launch without challenge host activity");
                        param.setResult(null);
                        return;
                    }

                    log("intercept instrumentation login flow: " + viewUrl);
                    showChallengeInApp(targetActivity, viewUrl);
                    param.setResult(null);
                }
            };
            int hooked = XposedBridge.hookAllMethods(android.app.Instrumentation.class, "execStartActivity", hook).size();
            log("hook Instrumentation.execStartActivity methods: " + hooked);
        } catch (Throwable t) {
            log("hookInstrumentationChallengeFlow failed: " + t);
        }
    }

    private static void hookCustomTabsChallengeFlow(ClassLoader appClassLoader) {
        try {
            String[] customTabsClasses = new String[] {
                    "androidx.browser.customtabs.CustomTabsIntent",
                    "android.support.customtabs.CustomTabsIntent"
            };
            boolean hooked = false;
            for (String className : customTabsClasses) {
                Class<?> customTabsIntentClz = XposedHelpers.findClassIfExists(className, appClassLoader);
                if (customTabsIntentClz == null) continue;
                hooked = true;
                log("hook CustomTabsIntent.launchUrl on " + className);
                XposedHelpers.findAndHookMethod(customTabsIntentClz, "launchUrl", Context.class, Uri.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof Context)) return;
                        if (!(param.args[1] instanceof Uri)) return;

                        Context context = (Context) param.args[0];
                        if (!TARGET_PACKAGE.equals(context.getPackageName())) return;
                        Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
                        SpotiPassPrefs.Config config = getCachedConfig(appContext);
                        if (!config.enabled) return;

                        Uri uri = (Uri) param.args[1];
                        String viewUrl = uri.toString();
                        if (!shouldOpenLoginFlowInWebView(viewUrl)) return;

                        Activity activity = resolveChallengeHostActivity(context);
                        if (activity == null) {
                            cachePendingChallenge(viewUrl, "custom tab without challenge host activity");
                            param.setResult(null);
                            return;
                        }

                        log("intercept custom tab login flow: " + viewUrl);
                        showChallengeInApp(activity, viewUrl);
                        param.setResult(null);
                    }
                });
            }
            if (!hooked) {
                log("CustomTabsIntent class not found");
            }
        } catch (Throwable t) {
            log("hookCustomTabsChallengeFlow failed: " + t);
        }
    }

    private static void hookChallengeLauncherFlow(ClassLoader appClassLoader) {
        try {
            Class<?> challengeLauncher = XposedHelpers.findClassIfExists(CHALLENGE_LAUNCHER_ACTIVITY, appClassLoader);
            if (challengeLauncher == null) {
                log("challenge launcher class not found: " + CHALLENGE_LAUNCHER_ACTIVITY);
                return;
            }

            XC_MethodHook startActivityHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
                    int intentArgIndex = findIntentArgIndex(param.args);
                    if (intentArgIndex < 0) return;
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET_PACKAGE.equals(activity.getPackageName())) return;

                    Context appContext = activity.getApplicationContext() == null ? activity : activity.getApplicationContext();
                    SpotiPassPrefs.Config config = getCachedConfig(appContext);

                    Object arg = param.args[intentArgIndex];
                    if (!(arg instanceof Intent)) return;
                    Intent intent = (Intent) arg;
                    String viewUrl = extractViewUrl(intent);
                    logChallengeLauncherIntent("challenge-start", intent, viewUrl);
                    if (viewUrl == null || viewUrl.isEmpty()) return;
                    if (!isHttpUrl(viewUrl)) return;
                    if (isAuthCallbackUrl(viewUrl)) {
                        log("allow auth callback from challenge launcher: " + viewUrl);
                        return;
                    }
                    if (!config.enabled) {
                        log("challenge launcher startActivity skip: config disabled");
                        return;
                    }

                    Activity targetActivity = resolveChallengeHostActivity(activity);
                    if (targetActivity == null) {
                        cachePendingChallenge(viewUrl, "challenge launcher start without challenge host activity");
                        param.setResult(null);
                        return;
                    }

                    log("intercept challenge launcher startActivity: " + viewUrl);
                    showChallengeInApp(targetActivity, viewUrl);
                    param.setResult(null);
                }
            };
            int hooked = 0;
            hooked += XposedBridge.hookAllMethods(challengeLauncher, "startActivity", startActivityHook).size();
            log("hook challenge launcher startActivity methods: " + hooked);

            XposedBridge.hookAllMethods(challengeLauncher, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET_PACKAGE.equals(activity.getPackageName())) return;
                    Intent intent = activity.getIntent();
                    String viewUrl = extractViewUrl(intent);
                    logChallengeLauncherIntent("challenge-onCreate", intent, viewUrl);
                }
            });
            log("hook challenge launcher onCreate methods installed");
        } catch (Throwable t) {
            log("hookChallengeLauncherFlow failed: " + t);
        }
    }

    private static void hookChallengeBridgeLaunch(ClassLoader appClassLoader) {
        try {
            Class<?> bridge = XposedHelpers.findClassIfExists("p.jk8", appClassLoader);
            if (bridge == null) {
                log("challenge bridge class not found: p.jk8");
                return;
            }
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
                    if (param.args == null || param.args.length < 2) return;
                    if (!(param.args[0] instanceof Context)) return;
                    if (!(param.args[1] instanceof Uri)) return;

                    Context context = (Context) param.args[0];
                    if (!TARGET_PACKAGE.equals(context.getPackageName())) return;

                    String url = normalizeHttpUrl((Uri) param.args[1]);
                    if (url == null || url.isEmpty()) return;

                    String contextClass = context.getClass().getName();
                    boolean fromChallengeLauncher = CHALLENGE_LAUNCHER_ACTIVITY.equals(contextClass);
                    if (isAuthCallbackUrl(url)) {
                        log("allow auth callback from challenge bridge: ctx=" + contextClass + ", url=" + url);
                        return;
                    }
                    if (!fromChallengeLauncher && !isLoginFlowUrl(url)) return;

                    Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
                    SpotiPassPrefs.Config config = getCachedConfig(appContext);
                    if (!config.enabled) {
                        log("challenge bridge launch skip: config disabled, ctx=" + contextClass + ", url=" + url);
                        return;
                    }

                    Activity activity = resolveChallengeHostActivity(context);

                    log("intercept challenge bridge launch: ctx=" + contextClass + ", url=" + url);
                    if (activity == null) {
                        cachePendingChallenge(url, "challenge bridge without challenge host activity");
                        param.setResult(null);
                        return;
                    }
                    showChallengeInApp(activity, url);
                    param.setResult(null);
                }
            };

            int hooked = 0;
            ArrayList<String> names = new ArrayList<>();
            for (Method method : bridge.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) continue;
                if (!Context.class.isAssignableFrom(params[0])) continue;
                if (!Uri.class.isAssignableFrom(params[1])) continue;
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                    if (names.size() < 8) names.add(method.getName());
                } catch (Throwable ignored) {
                }
            }

            if (hooked == 0) {
                log("challenge bridge method with (Context,Uri) not found in " + bridge.getName());
                return;
            }
            log("hook challenge bridge launch methods: " + hooked + ", names=" + names);
        } catch (Throwable t) {
            log("hookChallengeBridgeLaunch failed: " + t);
        }
    }

    private static void logChallengeLauncherIntent(String source, Intent intent, String url) {
        if (intent == null) return;
        String action = intent.getAction();
        String pkg = intent.getPackage();
        String cmp = intent.getComponent() == null ? "" : intent.getComponent().flattenToShortString();
        String u = url;
        if (u == null || u.isEmpty()) {
            Uri data = intent.getData();
            u = data == null ? "" : data.toString();
        }
        if (u.length() > 180) u = u.substring(0, 180) + "...";
        String key = source + "|" + action + "|" + pkg + "|" + cmp + "|" + u;
        if (loggedViewIntentKeys.add(key)) {
            log("challenge intent [" + source + "]: action=" + action
                    + ", pkg=" + (pkg == null ? "" : pkg)
                    + ", cmp=" + cmp
                    + ", url=" + u);
        }
    }

    private static int findIntentArgIndex(Object[] args) {
        if (args == null || args.length == 0) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) return i;
        }
        return -1;
    }

    private static void maybeLogViewIntent(String source, Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String scheme = data.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        String host = normalizeHost(data.getHost());
        if (host == null || host.isEmpty()) return;
        boolean related =
                host.endsWith(".spotify.com")
                        || host.endsWith(".google.com")
                        || host.endsWith(".gstatic.com")
                        || host.endsWith(".gstatic.cn")
                        || "www.recaptcha.net".equals(host);
        if (!related) return;
        String action = intent.getAction();
        String pkg = intent.getPackage();
        String cmp = intent.getComponent() == null ? "" : intent.getComponent().flattenToShortString();
        String url = data.toString();
        if (url.length() > 160) url = url.substring(0, 160) + "...";
        String key = source + "|" + action + "|" + host + "|" + pkg + "|" + cmp + "|" + url;
        if (loggedViewIntentKeys.add(key)) {
            log("view intent [" + source + "]: action=" + action
                    + ", host=" + host
                    + ", pkg=" + (pkg == null ? "" : pkg)
                    + ", cmp=" + cmp
                    + ", url=" + url);
        }
    }

    private static void interceptChallengeLaunch(XC_MethodHook.MethodHookParam param, int intentArgIndex) {
        if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
        if (param == null || param.args == null || param.args.length <= intentArgIndex) return;
        if (!(param.thisObject instanceof Activity)) return;
        Object arg = param.args[intentArgIndex];
        if (!(arg instanceof Intent)) return;

        Activity activity = (Activity) param.thisObject;
        if (!TARGET_PACKAGE.equals(activity.getPackageName())) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        Context context = activity.getApplicationContext();
        if (context == null) return;
        SpotiPassPrefs.Config config = getCachedConfig(context);
        if (!config.enabled) return;

        Intent intent = (Intent) arg;
        maybeLogViewIntent("activity", intent);
        String viewUrl = extractViewUrl(intent);
        if (viewUrl == null || viewUrl.isEmpty()) return;
        if (!shouldHandleLoginFlowIntent(intent, viewUrl)) return;

        Activity targetActivity = resolveChallengeHostActivity(activity);
        if (targetActivity == null) {
            cachePendingChallenge(viewUrl, "activity launch without challenge host activity");
            param.setResult(null);
            return;
        }

        log("intercept external login flow: " + viewUrl);
        showChallengeInApp(targetActivity, viewUrl);
        param.setResult(null);
    }

    private static void interceptChallengeLaunchFromContext(XC_MethodHook.MethodHookParam param, int intentArgIndex) {
        if (Boolean.TRUE.equals(challengeIntentGuard.get())) return;
        if (param == null || param.args == null || param.args.length <= intentArgIndex) return;
        Object arg = param.args[intentArgIndex];
        if (!(arg instanceof Intent)) return;
        if (!(param.thisObject instanceof Context)) return;

        Context context = (Context) param.thisObject;
        if (!TARGET_PACKAGE.equals(context.getPackageName())) return;
        SpotiPassPrefs.Config config = getCachedConfig(context.getApplicationContext() == null ? context : context.getApplicationContext());
        if (!config.enabled) return;

        Intent intent = (Intent) arg;
        maybeLogViewIntent("context", intent);
        String viewUrl = extractViewUrl(intent);
        if (viewUrl == null || viewUrl.isEmpty()) return;
        if (!shouldHandleLoginFlowIntent(intent, viewUrl)) return;

        Activity activity = resolveChallengeHostActivity(context);
        if (activity == null) {
            cachePendingChallenge(viewUrl, "context launch without challenge host activity");
            param.setResult(null);
            return;
        }

        log("intercept context external login flow: " + viewUrl);
        showChallengeInApp(activity, viewUrl);
        param.setResult(null);
    }

    private static Activity resolveAliveTargetActivity(Context context) {
        if (context instanceof Activity) {
            Activity direct = (Activity) context;
            if (isAliveTargetActivity(direct)) {
                return direct;
            }
        }
        Activity activity = lastTargetActivity == null ? null : lastTargetActivity.get();
        if (!isAliveTargetActivity(activity)) return null;
        return activity;
    }

    private static Activity resolveChallengeHostActivity(Context context) {
        Activity stable = lastStableTargetActivity == null ? null : lastStableTargetActivity.get();
        if (isAliveTargetActivity(stable) && !isChallengeLauncherActivity(stable)) {
            return stable;
        }

        if (context instanceof Activity) {
            Activity direct = (Activity) context;
            if (isAliveTargetActivity(direct) && !isChallengeLauncherActivity(direct)) {
                return direct;
            }
        }

        Activity latest = lastTargetActivity == null ? null : lastTargetActivity.get();
        if (isAliveTargetActivity(latest) && !isChallengeLauncherActivity(latest)) {
            return latest;
        }
        return null;
    }

    private static boolean isAliveTargetActivity(Activity activity) {
        if (activity == null) return false;
        if (!TARGET_PACKAGE.equals(activity.getPackageName())) return false;
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static boolean isChallengeLauncherActivity(Activity activity) {
        if (activity == null) return false;
        return CHALLENGE_LAUNCHER_ACTIVITY.equals(activity.getClass().getName());
    }

    private static void cachePendingChallenge(String challengeUrl, String reason) {
        if (challengeUrl == null || challengeUrl.isEmpty()) return;
        pendingChallengeUrl.set(challengeUrl);
        log("cache pending challenge (" + reason + "): " + challengeUrl);
    }

    private static void replayPendingChallengeIfNeeded(Activity activity, SpotiPassPrefs.Config config) {
        if (activity == null || config == null) return;
        String challengeUrl = pendingChallengeUrl.getAndSet(null);
        if (challengeUrl == null || challengeUrl.isEmpty()) return;
        if (!isHttpUrl(challengeUrl)) return;
        if (!config.enabled) return;
        if (isAuthCallbackUrl(challengeUrl)) {
            log("replay pending auth callback in app: " + challengeUrl);
            dispatchAuthCallbackIntent(activity, challengeUrl);
            return;
        }
        log("replay pending login flow in app: " + challengeUrl);
        showChallengeInApp(activity, challengeUrl);
    }

    private static String extractViewUrl(Intent intent) {
        if (intent == null) return null;
        String fromData = normalizeHttpUrl(intent.getData());
        if (fromData != null) return fromData;

        try {
            Object extra = intent.getParcelableExtra(TWA_FALLBACK_LAUNCH_URL_EXTRA);
            if (extra instanceof Uri) {
                String fromExtraUri = normalizeHttpUrl((Uri) extra);
                if (fromExtraUri != null) return fromExtraUri;
            }
        } catch (Throwable ignored) {
        }

        try {
            String extraUrl = intent.getStringExtra(TWA_FALLBACK_LAUNCH_URL_EXTRA);
            if (extraUrl != null && !extraUrl.isEmpty()) {
                String normalized = normalizeHttpUrl(Uri.parse(extraUrl));
                if (normalized != null) return normalized;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String normalizeHttpUrl(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
        return uri.toString();
    }

    private static boolean isHttpUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String scheme = Uri.parse(url).getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isChallengeLauncherIntent(Intent intent) {
        if (intent == null) return false;
        if (intent.getComponent() == null) return false;
        String className = intent.getComponent().getClassName();
        return CHALLENGE_LAUNCHER_ACTIVITY.equals(className);
    }

    private static boolean shouldHandleLoginFlowIntent(Intent intent, String viewUrl) {
        if (viewUrl == null || viewUrl.isEmpty()) return false;
        if (isAuthCallbackUrl(viewUrl)) return false;
        if (isLoginFlowUrl(viewUrl)) return true;
        return isChallengeLauncherIntent(intent) && isHttpUrl(viewUrl);
    }

    private static boolean shouldOpenLoginFlowInWebView(String viewUrl) {
        if (viewUrl == null || viewUrl.isEmpty()) return false;
        if (!isHttpUrl(viewUrl)) return false;
        if (isAuthCallbackUrl(viewUrl)) return false;
        return isLoginFlowUrl(viewUrl);
    }

    private static boolean isChallengeUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = normalizeHost(uri.getHost());
            if (!CHALLENGE_HOST.equals(host)) return false;
            String scheme = uri.getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLoginFlowUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            String host = normalizeHost(uri.getHost());
            if (host == null || host.isEmpty()) return false;
            if (CHALLENGE_HOST.equals(host)) return true;
            if ("accounts.spotify.com".equals(host)) return true;
            if ("partner-accounts.spotify.com".equals(host)) return true;
            if ("auth-callback.spotify.com".equals(host)) return true;
            if (RECAPTCHA_NET_HOST.equals(host)) return true;
            if (RECAPTCHA_GOOGLE_HOST.equals(host)) return true;
            return host.startsWith("accounts-") && host.endsWith(".spotify.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAuthCallbackUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = normalizeHost(uri.getHost());
            if ("auth-callback.spotify.com".equals(host)) return true;
            if ("callback".equals(host)) return true;
            String scheme = uri.getScheme();
            return "spotify".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void showChallengeInApp(Activity activity, String challengeUrl) {
        Runnable showTask = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!challengeDialogShowing.compareAndSet(false, true)) return;

            WebView webView = new WebView(activity);
            configureChallengeWebView(webView);

            final AlertDialog[] holder = new AlertDialog[1];
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    logChallengeWebViewNavigation("page-start", url);
                    super.onPageStarted(view, url, favicon);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    logChallengeWebViewNavigation("page-finish", url);
                    super.onPageFinished(view, url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) return false;
                    logChallengeWebViewRequest("override", request);
                    return handleChallengeNavigation(activity, view, request.getUrl().toString(), holder[0]);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    logChallengeWebViewNavigation("override-legacy", url);
                    return handleChallengeNavigation(activity, view, url, holder[0]);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) return null;
                    logChallengeWebViewRequest("intercept", request);
                    String original = request.getUrl().toString();
                    String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(original);
                    if (isSameString(rewritten, original)) return null;
                    return fetchWebResourceViaRewrittenUrl(rewritten, request);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    logChallengeWebViewNavigation("intercept-legacy", url);
                    String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(url);
                    if (isSameString(rewritten, url)) return null;
                    return fetchWebResourceViaRewrittenUrl(rewritten, null);
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                    logChallengeWebViewHttpError(request, errorResponse);
                    super.onReceivedHttpError(view, request, errorResponse);
                }

                @Override
                public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                    if (handleChallengeWebViewHttpAuthRequest(view, handler, host, realm)) {
                        return;
                    }
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    logChallengeWebViewError(request, error);
                    super.onReceivedError(view, request, error);
                }
            });

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("SpotiPass \u767b\u5f55")
                    .setView(webView)
                    .setCancelable(true)
                    .setNegativeButton("\u5173\u95ed", null)
                    .create();
            holder[0] = dialog;
            dialog.setOnDismissListener(d -> {
                challengeDialogShowing.set(false);
                try {
                    webView.stopLoading();
                    webView.destroy();
                } catch (Throwable ignored) {
                }
                clearWebViewLoginProxyOverride(activity, "dialog dismissed");
            });
            dialog.show();

            String firstUrl = maybeRewriteRecaptchaUrlForCurrentConfig(challengeUrl);
            loadChallengeUrlInWebView(activity, webView, firstUrl);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showTask.run();
        } else {
            activity.runOnUiThread(showTask);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configureChallengeWebView(WebView webView) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    private static void loadChallengeUrlInWebView(Activity activity, WebView webView, String url) {
        if (webView == null || url == null || url.isEmpty()) return;
        if (activity == null) {
            webView.loadUrl(url);
            return;
        }

        Context appContext = activity.getApplicationContext();
        if (appContext == null) {
            webView.loadUrl(url);
            return;
        }

        SpotiPassPrefs.Config config = getCachedConfig(appContext);
        if (config == null || !config.enabled) {
            webView.loadUrl(url);
            return;
        }

        if (SpotiPassKeys.isLoginProxyMode(config.loginMode)) {
            LoginHttpProxyConfig proxyConfig = getActiveLoginProxyConfig();
            if (proxyConfig == null) {
                webView.loadUrl(url);
                return;
            }
            applyWebViewLoginProxyOverride(activity, webView, url, proxyConfig);
            return;
        }

        if (SpotiPassKeys.isLoginDnsMode(config.loginMode)) {
            applyWebViewLoginDnsOverride(activity, webView, url, config);
            return;
        }

        webView.loadUrl(url);
    }

    private static void applyWebViewLoginProxyOverride(Activity activity, WebView webView, String url, LoginHttpProxyConfig config) {
        if (activity == null || webView == null || url == null || url.isEmpty() || config == null) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            logLoginProxyOnce("webViewProxyUnsupported",
                    "current WebView implementation does not support proxy override; login WebView requests will remain direct");
            webView.loadUrl(url);
            return;
        }

        WebViewProxyOverrideState state = buildWebViewLoginProxyOverrideState(config);
        if (state == null || state.proxyConfig == null) {
            webView.loadUrl(url);
            return;
        }

        replaceActiveWebViewLocalProxyHandle(state.localProxyHandle);
        try {
            ProxyController.getInstance().setProxyOverride(
                    state.proxyConfig,
                    mainThreadExecutor(activity),
                    () -> {
                        logRuntime(
                                "已为登录 WebView 应用代理覆写：" + state.displayTarget,
                                "applied login WebView proxy override: " + state.displayTarget
                        );
                        if (!challengeDialogShowing.get()) return;
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        webView.loadUrl(url);
                    }
            );
        } catch (Throwable t) {
            replaceActiveWebViewLocalProxyHandle(null);
            log("apply WebView login proxy override failed: " + t);
            webView.loadUrl(url);
        }
    }

    private static void applyWebViewLoginDnsOverride(Activity activity, WebView webView, String url, SpotiPassPrefs.Config config) {
        if (activity == null || webView == null || url == null || url.isEmpty() || config == null) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            logLoginProxyOnce("webViewDnsProxyUnsupported",
                    "current WebView implementation does not support proxy override; login WebView DNS mode will remain partial");
            webView.loadUrl(url);
            return;
        }

        WebViewProxyOverrideState state = buildWebViewLoginDnsOverrideState(config);
        if (state == null || state.proxyConfig == null) {
            webView.loadUrl(url);
            return;
        }

        replaceActiveWebViewLocalProxyHandle(state.localProxyHandle);
        try {
            ProxyController.getInstance().setProxyOverride(
                    state.proxyConfig,
                    mainThreadExecutor(activity),
                    () -> {
                        logRuntime(
                                "已为登录 WebView 应用 DNS 覆写代理：" + state.displayTarget,
                                "applied login WebView DNS override proxy: " + state.displayTarget
                        );
                        if (!challengeDialogShowing.get()) return;
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        webView.loadUrl(url);
                    }
            );
        } catch (Throwable t) {
            replaceActiveWebViewLocalProxyHandle(null);
            log("apply WebView login DNS override failed: " + t);
            webView.loadUrl(url);
        }
    }

    private static void clearWebViewLoginProxyOverride(Activity activity, String reason) {
        if (activity == null) {
            replaceActiveWebViewLocalProxyHandle(null);
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            replaceActiveWebViewLocalProxyHandle(null);
            return;
        }
        try {
            ProxyController.getInstance().clearProxyOverride(
                    mainThreadExecutor(activity),
                    () -> {
                        replaceActiveWebViewLocalProxyHandle(null);
                        logRuntime(
                                "已清理登录 WebView 代理覆写（" + reason + "）",
                                "cleared login WebView proxy override (" + reason + ")"
                        );
                    }
            );
        } catch (Throwable t) {
            replaceActiveWebViewLocalProxyHandle(null);
            logLoginProxyOnce("clearWebViewProxyOverrideFailed|" + t.getClass().getName(),
                    "clear WebView login proxy override failed: " + t);
        }
    }

    private static WebViewProxyOverrideState buildWebViewLoginProxyOverrideState(LoginHttpProxyConfig config) {
        if (config == null) return null;
        WebViewLoginProxyRelay relay = null;
        try {
            String authHeader = config.hasCredentials() ? config.proxyAuthorizationHeader() : "";
            relay = WebViewLoginProxyRelay.start(
                    config.host,
                    config.port,
                    config.useTlsToProxy,
                    authHeader,
                    message -> log(message)
            );
            ProxyConfig proxyConfig = buildWebViewLoopbackProxyConfig(relay.localProxyRule());
            if (proxyConfig == null) {
                closeWebViewLocalProxyHandle(relay);
                return null;
            }
            return new WebViewProxyOverrideState(proxyConfig, relay.describeRoute(), relay);
        } catch (Throwable t) {
            closeWebViewLocalProxyHandle(relay);
            logLoginProxyOnce("buildWebViewLoginProxyConfigFailed|" + t.getClass().getName(),
                    "build WebView login proxy config failed: " + t);
            return null;
        }
    }

    private static WebViewProxyOverrideState buildWebViewLoginDnsOverrideState(SpotiPassPrefs.Config config) {
        if (config == null || !SpotiPassKeys.isLoginDnsMode(config.loginMode)) return null;
        LocalLoginDnsProxyServer server = null;
        try {
            Map<String, List<byte[]>> rules = getParsedLoginDnsRules(config.loginDnsRules);
            server = LocalLoginDnsProxyServer.start(rules, message -> log(message));
            ProxyConfig proxyConfig = buildWebViewLoopbackProxyConfig(server.localProxyRule());
            if (proxyConfig == null) {
                closeWebViewLocalProxyHandle(server);
                return null;
            }
            return new WebViewProxyOverrideState(proxyConfig, server.describeRoute(), server);
        } catch (Throwable t) {
            closeWebViewLocalProxyHandle(server);
            logLoginProxyOnce("buildWebViewLoginDnsProxyConfigFailed|" + t.getClass().getName(),
                    "build WebView login DNS proxy config failed: " + t);
            return null;
        }
    }

    private static ProxyConfig buildWebViewLoopbackProxyConfig(String proxyRule) {
        if (proxyRule == null || proxyRule.isEmpty()) return null;
        try {
            ProxyConfig.Builder builder = new ProxyConfig.Builder();
            builder.addProxyRule(proxyRule);

            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                builder.setReverseBypassEnabled(true);
                for (String rule : WEBVIEW_LOGIN_PROXY_REVERSE_BYPASS_RULES) {
                    builder.addBypassRule(rule);
                }
            } else {
                logLoginProxyOnce("webViewReverseBypassUnsupported",
                        "current WebView implementation does not support reverse bypass; all WebView requests during login will use the proxy");
            }
            return builder.build();
        } catch (Throwable t) {
            logLoginProxyOnce("buildWebViewLoopbackProxyConfigFailed|" + t.getClass().getName(),
                    "build WebView loopback proxy config failed: " + t);
            return null;
        }
    }

    private static void replaceActiveWebViewLocalProxyHandle(Closeable handle) {
        Closeable previous;
        synchronized (webViewLocalProxyLock) {
            previous = activeWebViewLocalProxyHandle;
            activeWebViewLocalProxyHandle = handle;
        }
        if (previous != handle) {
            closeWebViewLocalProxyHandle(previous);
        }
    }

    private static void closeWebViewLocalProxyHandle(Closeable handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (Throwable ignored) {
        }
    }

    private static Executor mainThreadExecutor(Activity activity) {
        return command -> {
            if (command == null) return;
            if (activity == null) {
                command.run();
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
                return;
            }
            activity.runOnUiThread(command);
        };
    }

    private static void logChallengeWebViewNavigation(String stage, String url) {
        if (!isHttpUrl(url)) return;
        logRuntime(
                "登录 WebView [" + stage + "] " + url,
                "login WebView [" + stage + "] " + url
        );
    }

    private static void logChallengeWebViewRequest(String stage, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return;
        String url = request.getUrl().toString();
        if (!isHttpUrl(url)) return;

        String method = request.getMethod();
        boolean mainFrame = request.isForMainFrame();
        boolean gesture = request.hasGesture();
        boolean redirect = safeIsRedirect(request);
        String headers = formatRequestHeadersForLog(request.getRequestHeaders());

        logRuntime(
                "登录 WebView 请求[" + stage + "] "
                        + trimToEmpty(method) + " " + url
                        + ", mainFrame=" + mainFrame
                        + ", gesture=" + gesture
                        + ", redirect=" + redirect
                        + headers,
                "login WebView request [" + stage + "] "
                        + trimToEmpty(method) + " " + url
                        + ", mainFrame=" + mainFrame
                        + ", gesture=" + gesture
                        + ", redirect=" + redirect
                        + headers
        );
    }

    private static void logChallengeWebViewHttpError(WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request == null || request.getUrl() == null || errorResponse == null) return;
        String url = request.getUrl().toString();
        if (!isHttpUrl(url)) return;
        int statusCode = errorResponse.getStatusCode();
        String reason = trimToEmpty(errorResponse.getReasonPhrase());
        logRuntime(
                "登录 WebView HTTP 错误 " + statusCode + " " + reason + " <- " + url,
                "login WebView HTTP error " + statusCode + " " + reason + " <- " + url
        );
    }

    private static boolean handleChallengeWebViewHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
        String normalizedHost = normalizeAuthRequestHost(host);
        String safeRealm = trimToEmpty(realm);
        LoginHttpProxyConfig config = getActiveLoginProxyConfig();
        boolean matchesProxy = isChallengeWebViewProxyAuthHost(normalizedHost, config);
        boolean hasCredentials = config != null && config.hasCredentials();

        logRuntime(
                "登录 WebView 认证请求 host=" + trimToEmpty(host)
                        + ", realm=" + safeRealm
                        + ", proxyMatch=" + matchesProxy
                        + ", hasCredentials=" + hasCredentials,
                "login WebView auth request host=" + trimToEmpty(host)
                        + ", realm=" + safeRealm
                        + ", proxyMatch=" + matchesProxy
                        + ", hasCredentials=" + hasCredentials
        );

        if (handler == null || config == null || !matchesProxy || !hasCredentials) {
            return false;
        }

        try {
            if (view != null) {
                view.setHttpAuthUsernamePassword(host, realm, config.username, config.password);
            }
            handler.proceed(config.username, config.password);
            logLoginProxyRuntimeOnce(
                    "webViewProxyAuth|" + normalizedHost + "|" + safeRealm + "|" + config.target(),
                    "已为登录 WebView 提交代理认证：" + config.displayTarget()
                            + (safeRealm.isEmpty() ? "" : "，realm=" + safeRealm),
                    "submitted login WebView proxy authentication for " + config.displayTarget()
                            + (safeRealm.isEmpty() ? "" : ", realm=" + safeRealm)
            );
            return true;
        } catch (Throwable t) {
            logLoginProxyOnce(
                    "webViewProxyAuthFailed|" + normalizedHost + "|" + t.getClass().getName(),
                    "login WebView proxy authentication failed for " + trimToEmpty(host) + ": " + t
            );
            try {
                handler.cancel();
            } catch (Throwable ignored) {
            }
            return true;
        }
    }

    private static void logChallengeWebViewError(WebResourceRequest request, WebResourceError error) {
        if (request == null || request.getUrl() == null || error == null) return;
        String url = request.getUrl().toString();
        if (!isHttpUrl(url)) return;
        CharSequence description = error.getDescription();
        String reason = description == null ? "" : description.toString();
        logRuntime(
                "登录 WebView 加载失败 " + error.getErrorCode() + " " + reason + " <- " + url,
                "login WebView load failed " + error.getErrorCode() + " " + reason + " <- " + url
        );
    }

    private static boolean safeIsRedirect(WebResourceRequest request) {
        if (request == null) return false;
        try {
            return request.isRedirect();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String formatRequestHeadersForLog(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return ", headers={}";
        StringBuilder sb = new StringBuilder(", headers={");
        boolean first = true;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            if (!first) sb.append(", ");
            first = false;

            String key = entry.getKey();
            String normalized = key.trim().toLowerCase(Locale.US);
            String value = entry.getValue();
            if ("cookie".equals(normalized)
                    || "authorization".equals(normalized)
                    || "proxy-authorization".equals(normalized)
                    || "set-cookie".equals(normalized)) {
                value = "<redacted>";
            }
            sb.append(key).append('=').append(truncateForLog(value, 160));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String truncateForLog(String value, int maxChars) {
        if (value == null || value.isEmpty()) return "";
        if (maxChars <= 0 || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "...";
    }

    private static String normalizeAuthRequestHost(String host) {
        String normalized = normalizeHost(host);
        if (normalized == null || normalized.isEmpty()) return normalized;
        if (normalized.startsWith("[")) {
            int closing = normalized.indexOf(']');
            if (closing > 0 && closing < normalized.length() - 1 && normalized.charAt(closing + 1) == ':') {
                return normalized.substring(0, closing + 1);
            }
            return normalized;
        }
        int firstColon = normalized.indexOf(':');
        int lastColon = normalized.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return normalized.substring(0, firstColon);
        }
        return normalized;
    }

    private static boolean isChallengeWebViewProxyAuthHost(String authHost, LoginHttpProxyConfig config) {
        if (authHost == null || authHost.isEmpty() || config == null) return false;
        String configHost = normalizeAuthRequestHost(config.host);
        if (configHost == null || configHost.isEmpty()) return false;
        return configHost.equals(authHost);
    }

    private static boolean handleChallengeNavigation(Activity activity, WebView view, String url, AlertDialog dialog) {
        if (url == null || url.isEmpty()) return false;
        if (isAuthCallbackUrl(url)) {
            dispatchAuthCallbackIntent(activity, url);
            tryDismissDialog(dialog);
            return true;
        }
        String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(url);
        if (!isSameString(rewritten, url)) {
            logRecaptchaRewriteOnce(url, rewritten);
            view.loadUrl(rewritten);
            return true;
        }
        return false;
    }

    private static void dispatchAuthCallbackIntent(Activity activity, String url) {
        try {
            Intent callbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            callbackIntent.setPackage(TARGET_PACKAGE);
            callbackIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            challengeIntentGuard.set(Boolean.TRUE);
            activity.startActivity(callbackIntent);
        } catch (Throwable t) {
            log("dispatchAuthCallbackIntent failed: " + t);
        } finally {
            challengeIntentGuard.set(Boolean.FALSE);
        }
    }

    private static void tryDismissDialog(AlertDialog dialog) {
        if (dialog == null) return;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private static WebResourceResponse fetchWebResourceViaRewrittenUrl(String rewrittenUrl, WebResourceRequest request) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(rewrittenUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            if (request != null) {
                String method = request.getMethod();
                if (method != null && !method.isEmpty()) {
                    conn.setRequestMethod(method);
                }
                Map<String, String> reqHeaders = request.getRequestHeaders();
                if (reqHeaders != null) {
                    for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;

            String contentType = conn.getContentType();
            String mimeType = parseMimeType(contentType);
            String encoding = parseCharset(contentType);
            if (encoding == null || encoding.isEmpty()) {
                encoding = conn.getContentEncoding();
            }
            if (encoding == null || encoding.isEmpty()) {
                encoding = "utf-8";
            }

            Map<String, String> responseHeaders = new LinkedHashMap<>();
            Map<String, List<String>> headerFields = conn.getHeaderFields();
            if (headerFields != null) {
                for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
                    String key = e.getKey();
                    if (key == null) continue;
                    List<String> values = e.getValue();
                    if (values == null || values.isEmpty()) continue;
                    responseHeaders.put(key, values.get(0));
                }
            }
            String reason = conn.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = "OK";

            return new WebResourceResponse(mimeType, encoding, code, reason, responseHeaders, stream);
        } catch (Throwable t) {
            log("fetchWebResourceViaRewrittenUrl failed: " + t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private static String parseMimeType(String contentType) {
        if (contentType == null || contentType.isEmpty()) return "application/octet-stream";
        int idx = contentType.indexOf(';');
        if (idx < 0) return contentType.trim();
        return contentType.substring(0, idx).trim();
    }

    private static String parseCharset(String contentType) {
        if (contentType == null || contentType.isEmpty()) return null;
        String[] parts = contentType.split(";");
        for (String part : parts) {
            if (part == null) continue;
            String t = part.trim().toLowerCase(Locale.US);
            if (t.startsWith("charset=")) {
                return t.substring("charset=".length()).trim();
            }
        }
        return null;
    }

    private static void ensureCoreState(Context context) {
        SpotiPassPrefs prefs = SpotiPassPrefs.getInstance(context);
        SpotiPassPrefs.Config config = prefs.getConfig();
        cachedConfig = config;
        loggedLoginProxyKeys.clear();
        configLoaded.set(true);
    }

    private static void ensureCoreStateAsync(Context context) {
        if (context == null) return;
        if (!ensuringCore.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                ensureCoreState(context);
            } catch (Throwable t) {
                log("ensureCoreStateAsync failed: " + t);
            } finally {
                ensuringCore.set(false);
            }
        }, "spotipass-core").start();
    }

    /**
     * Hook URL.openConnection() 以拦截 recaptcha URL 重写。
     */
    private static void hookUrlOpenConnection() {
        try {
            XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(recaptchaRewriteGuard.get())) return;

                    Context context = currentApplication();
                    if (context == null) return;
                    SpotiPassPrefs.Config config = getCachedConfig(context);
                    if (!config.enabled) return;

                    URL url = (URL) param.thisObject;
                    String original = url.toString();
                    String rewritten = rewriteRecaptchaUrlIfNeeded(original);
                    if (isSameString(rewritten, original)) return;

                    URLConnection conn = openRewrittenConnection(rewritten, null);
                    if (conn != null) {
                        logRecaptchaRewriteOnce(original, rewritten);
                        param.setResult(conn);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(java.net.URL.class, "openConnection",
                    java.net.Proxy.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(recaptchaRewriteGuard.get())) return;

                            java.net.URL url = (java.net.URL) param.thisObject;

                            Context context = currentApplication();
                            if (context == null) return;

                            SpotiPassPrefs.Config config = getCachedConfig(context);
                            if (!config.enabled) return;

                            String original = url.toString();
                            String rewritten = rewriteRecaptchaUrlIfNeeded(original);
                            if (!isSameString(rewritten, original)) {
                                java.net.Proxy proxy = (java.net.Proxy) param.args[0];
                                URLConnection conn = openRewrittenConnection(rewritten, proxy);
                                if (conn != null) {
                                    logRecaptchaRewriteOnce(original, rewritten);
                                    param.setResult(conn);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hookUrlOpenConnection failed: " + t);
        }
    }

    private static void hookLoginDns(ClassLoader cl) {
        hookInetAddressDns();
        hookOkHttpDnsSystem(cl);
    }

    private static void hookLoginProxy(ClassLoader cl) {
        try {
            Class<?> builderClass = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient$Builder", cl);
            if (builderClass == null) {
                log("okhttp OkHttpClient.Builder not found");
                return;
            }

            XC_MethodHook buildHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyLoginProxyToOkHttpBuilder(param.thisObject, cl);
                }
            };

            int hooked = 0;
            ArrayList<String> names = new ArrayList<>();
            for (Method method : builderClass.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                if (!"okhttp3.OkHttpClient".equals(method.getReturnType().getName())) continue;
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, buildHook);
                    hooked++;
                    if (names.size() < 8) names.add(method.getName());
                } catch (Throwable ignored) {
                }
            }

            if (hooked == 0) {
                Class<?> okHttpClient = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient", cl);
                if (okHttpClient != null) {
                    for (Constructor<?> constructor : okHttpClient.getDeclaredConstructors()) {
                        Class<?>[] params = constructor.getParameterTypes();
                        if (params.length != 1 || !builderClass.isAssignableFrom(params[0])) continue;
                        try {
                            constructor.setAccessible(true);
                            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (param.args == null || param.args.length == 0) return;
                                    applyLoginProxyToOkHttpBuilder(param.args[0], cl);
                                }
                            });
                            hooked++;
                            if (names.size() < 8) names.add("<init>");
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            log("hook okhttp login proxy build methods: " + hooked + ", names=" + names);
        } catch (Throwable t) {
            log("hookLoginProxy failed: " + t);
        }
    }

    private static void hookRecaptchaUrlRewrite(ClassLoader cl) {
        hookWebViewRecaptchaUrlRewrite();
        hookOkHttpRequestUrlRewrite(cl);
    }

    private static void hookWebViewRecaptchaUrlRewrite() {
        try {
            XposedHelpers.findAndHookMethod(android.webkit.WebView.class, "loadUrl", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String original = (String) param.args[0];
                    String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(original);
                    if (!isSameString(rewritten, original)) {
                        param.args[0] = rewritten;
                        logRecaptchaRewriteOnce(original, rewritten);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(android.webkit.WebView.class, "loadUrl", String.class, Map.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String original = (String) param.args[0];
                    String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(original);
                    if (!isSameString(rewritten, original)) {
                        param.args[0] = rewritten;
                        logRecaptchaRewriteOnce(original, rewritten);
                    }
                }
            });
        } catch (Throwable t) {
            log("hookWebViewRecaptchaUrlRewrite failed: " + t);
        }
    }

    private static void hookOkHttpRequestUrlRewrite(ClassLoader cl) {
        try {
            Class<?> requestBuilder = XposedHelpers.findClassIfExists("okhttp3.Request$Builder", cl);
            if (requestBuilder == null) {
                log("okhttp Request.Builder not found");
                return;
            }
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) return;
                    Object firstArg = param.args[0];
                    if (!(firstArg instanceof String)) return;
                    String original = (String) firstArg;
                    String rewritten = maybeRewriteRecaptchaUrlForCurrentConfig(original);
                    if (!isSameString(rewritten, original)) {
                        param.args[0] = rewritten;
                        logRecaptchaRewriteOnce(original, rewritten);
                    }
                }
            };
            int hooked = 0;
            ArrayList<String> names = new ArrayList<>();
            for (Method method : requestBuilder.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                    if (names.size() < 8) names.add(method.getName());
                } catch (Throwable ignored) {
                }
            }
            log("hook okhttp Request.Builder string methods: " + hooked + ", names=" + names);
        } catch (Throwable t) {
            log("hookOkHttpRequestUrlRewrite failed: " + t);
        }
    }

    private static void hookInetAddressDns() {
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String host = (String) param.args[0];
                    InetAddress[] overridden = resolveLoginDnsOverride(host);
                    if (overridden != null && overridden.length > 0) {
                        param.setResult(overridden);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String host = (String) param.args[0];
                    InetAddress[] overridden = resolveLoginDnsOverride(host);
                    if (overridden != null && overridden.length > 0) {
                        param.setResult(overridden[0]);
                    }
                }
            });
        } catch (Throwable t) {
            log("hookInetAddressDns failed: " + t);
        }
    }

    private static void hookOkHttpDnsSystem(ClassLoader cl) {
        try {
            Class<?> dnsSystem = XposedHelpers.findClassIfExists("okhttp3.Dns$Companion$DnsSystem", cl);
            if (dnsSystem == null) {
                log("okhttp DnsSystem class not found");
                return;
            }
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) return;
                    Object hostArg = param.args[0];
                    if (!(hostArg instanceof String)) return;
                    String host = (String) hostArg;
                    InetAddress[] overridden = resolveLoginDnsOverride(host);
                    if (overridden != null && overridden.length > 0) {
                        param.setResult(Arrays.asList(overridden));
                    }
                }
            };
            int hooked = 0;
            ArrayList<String> names = new ArrayList<>();
            for (Method method : dnsSystem.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                if (!List.class.isAssignableFrom(method.getReturnType())) continue;
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, hook);
                    hooked++;
                    if (names.size() < 8) names.add(method.getName());
                } catch (Throwable ignored) {
                }
            }
            log("hook okhttp DnsSystem string->List methods: " + hooked + ", names=" + names);
        } catch (Throwable t) {
            log("hookOkHttpDnsSystem failed: " + t);
        }
    }

    private static void applyLoginProxyToOkHttpBuilder(Object builder, ClassLoader cl) {
        if (builder == null || cl == null) return;
        try {
            Class<?> builderClass = builder.getClass();

            Proxy explicitProxy = readBuilderProxy(builderClass, builder);
            if (explicitProxy != null && explicitProxy.type() != Proxy.Type.DIRECT) {
                logLoginProxyOnce("explicitProxy|" + explicitProxy.type(),
                        "skip login proxy injection on okhttp builder with explicit proxy: " + explicitProxy.type());
                return;
            }
            if (explicitProxy != null && explicitProxy.type() == Proxy.Type.DIRECT) {
                clearBuilderProxy(builderClass, builder);
            }

            ProxySelector existingSelector = readBuilderProxySelector(builderClass, builder);
            if (!(existingSelector instanceof LoginProxySelector)) {
                boolean selectorInjected = writeBuilderProxySelector(
                        builderClass,
                        builder,
                        new LoginProxySelector(existingSelector)
                );
                if (!selectorInjected) {
                    logLoginProxyOnce("selectorInjectionFailed",
                            "unable to inject okhttp login ProxySelector");
                }
            }

            LoginHttpProxyConfig config = getActiveLoginProxyConfig();
            if (config != null && config.useTlsToProxy) {
                SocketFactory existingSocketFactory = readBuilderSocketFactory(builderClass, builder);
                if (!isLoginProxySocketFactory(existingSocketFactory)) {
                    boolean socketFactoryInjected = writeBuilderSocketFactory(
                            builderClass,
                            builder,
                            new LoginProxyTlsSocketFactory(existingSocketFactory)
                    );
                    if (!socketFactoryInjected) {
                        logLoginProxyOnce("socketFactoryInjectionFailed",
                                "unable to inject okhttp login proxy TLS socket factory");
                    }
                }
            }

            Class<?> authClass = XposedHelpers.findClassIfExists("okhttp3.Authenticator", cl);
            if (authClass == null) {
                logLoginProxyOnce("authClassMissing", "okhttp Authenticator class not found");
                return;
            }

            Object existingProxyAuthenticator = readBuilderProxyAuthenticator(builderClass, builder, authClass);
            if (!isLoginProxyAuthenticator(existingProxyAuthenticator)) {
                Object wrapper = java.lang.reflect.Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{authClass},
                        new LoginProxyAuthenticatorHandler(existingProxyAuthenticator)
                );
                boolean authInjected = writeBuilderProxyAuthenticator(builderClass, builder, authClass, wrapper);
                if (!authInjected) {
                    logLoginProxyOnce("authInjectionFailed",
                            "unable to inject okhttp proxy authenticator");
                }
            }
        } catch (Throwable t) {
            logLoginProxyOnce("applyBuilderFailed|" + t.getClass().getName(),
                    "apply login proxy to okhttp builder failed: " + t);
        }
    }

    private static Proxy readBuilderProxy(Class<?> builderClass, Object builder) {
        Field field = findBestFieldByType(builderClass, Proxy.class, "proxy");
        Object value = readField(field, builder);
        return value instanceof Proxy ? (Proxy) value : null;
    }

    private static ProxySelector readBuilderProxySelector(Class<?> builderClass, Object builder) {
        Field field = findBestFieldByType(builderClass, ProxySelector.class, "proxy");
        Object value = readField(field, builder);
        return value instanceof ProxySelector ? (ProxySelector) value : null;
    }

    private static SocketFactory readBuilderSocketFactory(Class<?> builderClass, Object builder) {
        Field field = findBestExactFieldByType(builderClass, SocketFactory.class, "socket");
        Object value = readField(field, builder);
        return value instanceof SocketFactory ? (SocketFactory) value : null;
    }

    private static Object readBuilderProxyAuthenticator(Class<?> builderClass, Object builder, Class<?> authClass) {
        ArrayList<Field> fields = findAllFieldsByType(builderClass, authClass);
        for (Field field : fields) {
            Object value = readField(field, builder);
            if (isLoginProxyAuthenticator(value)) return value;
        }
        for (Field field : fields) {
            Object value = readField(field, builder);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean clearBuilderProxy(Class<?> builderClass, Object builder) {
        Method setter = findBestSingleArgMethod(builderClass, Proxy.class, "proxy");
        if (setter != null) {
            try {
                setter.invoke(builder, new Object[]{null});
                return true;
            } catch (Throwable ignored) {
            }
        }
        Field field = findBestFieldByType(builderClass, Proxy.class, "proxy");
        if (field != null) {
            try {
                field.setAccessible(true);
                field.set(builder, null);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean writeBuilderProxySelector(Class<?> builderClass, Object builder, ProxySelector proxySelector) {
        Method setter = findBestSingleArgMethod(builderClass, ProxySelector.class, "proxy");
        if (setter != null) {
            try {
                setter.invoke(builder, proxySelector);
                return true;
            } catch (Throwable ignored) {
            }
        }
        Field field = findBestFieldByType(builderClass, ProxySelector.class, "proxy");
        if (field != null) {
            try {
                field.setAccessible(true);
                field.set(builder, proxySelector);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean writeBuilderSocketFactory(Class<?> builderClass, Object builder, SocketFactory socketFactory) {
        Method setter = findBestSingleArgMethod(builderClass, SocketFactory.class, "socket");
        if (setter != null) {
            try {
                setter.invoke(builder, socketFactory);
                return true;
            } catch (Throwable ignored) {
            }
        }
        Field field = findBestExactFieldByType(builderClass, SocketFactory.class, "socket");
        if (field != null) {
            try {
                field.setAccessible(true);
                field.set(builder, socketFactory);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean writeBuilderProxyAuthenticator(Class<?> builderClass, Object builder, Class<?> authClass, Object proxyAuthenticator) {
        boolean wrote = false;

        ArrayList<Method> methods = findPreferredSingleArgMethods(builderClass, authClass, "proxy");
        if (methods.isEmpty()) {
            methods = findAllSingleArgMethods(builderClass, authClass);
        }
        for (Method setter : methods) {
            try {
                setter.invoke(builder, proxyAuthenticator);
                wrote = true;
            } catch (Throwable ignored) {
            }
        }

        ArrayList<Field> fields = findPreferredFieldsByType(builderClass, authClass, "proxy");
        if (fields.isEmpty()) {
            fields = findAllFieldsByType(builderClass, authClass);
        }
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                field.set(builder, proxyAuthenticator);
                wrote = true;
            } catch (Throwable ignored) {
            }
        }
        return wrote;
    }

    private static Method findBestSingleArgMethod(Class<?> type, Class<?> argType, String preferredNameToken) {
        if (type == null || argType == null) return null;
        ArrayList<Method> matches = findAllSingleArgMethods(type, argType);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);

        String token = preferredNameToken == null ? "" : preferredNameToken.toLowerCase(Locale.US);
        if (!token.isEmpty()) {
            for (Method method : matches) {
                if (method.getName().toLowerCase(Locale.US).contains(token)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static ArrayList<Method> findAllSingleArgMethods(Class<?> type, Class<?> argType) {
        ArrayList<Method> matches = new ArrayList<>();
        if (type == null || argType == null) return matches;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;
            if (params[0] != argType) continue;
            if (!isBuilderSetterReturnType(type, method.getReturnType())) continue;
            method.setAccessible(true);
            matches.add(method);
        }
        return matches;
    }

    private static ArrayList<Method> findPreferredSingleArgMethods(Class<?> type, Class<?> argType, String preferredNameToken) {
        ArrayList<Method> matches = findAllSingleArgMethods(type, argType);
        if (matches.isEmpty()) return matches;

        String token = preferredNameToken == null ? "" : preferredNameToken.toLowerCase(Locale.US);
        if (token.isEmpty()) return matches;

        ArrayList<Method> preferred = new ArrayList<>();
        for (Method method : matches) {
            if (method.getName().toLowerCase(Locale.US).contains(token)) {
                preferred.add(method);
            }
        }
        return preferred.isEmpty() ? matches : preferred;
    }

    private static boolean isBuilderSetterReturnType(Class<?> ownerType, Class<?> returnType) {
        if (returnType == Void.TYPE) return true;
        return returnType == ownerType || ownerType.isAssignableFrom(returnType);
    }

    private static Field findBestFieldByType(Class<?> type, Class<?> fieldType, String preferredNameToken) {
        if (type == null || fieldType == null) return null;
        ArrayList<Field> matches = findAllFieldsByType(type, fieldType);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);

        String token = preferredNameToken == null ? "" : preferredNameToken.toLowerCase(Locale.US);
        if (!token.isEmpty()) {
            for (Field field : matches) {
                if (field.getName().toLowerCase(Locale.US).contains(token)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static ArrayList<Field> findAllFieldsByType(Class<?> type, Class<?> fieldType) {
        ArrayList<Field> matches = new ArrayList<>();
        if (type == null || fieldType == null) return matches;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!fieldType.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                matches.add(field);
            }
        }
        return matches;
    }

    private static ArrayList<Field> findPreferredFieldsByType(Class<?> type, Class<?> fieldType, String preferredNameToken) {
        ArrayList<Field> matches = findAllFieldsByType(type, fieldType);
        if (matches.isEmpty()) return matches;

        String token = preferredNameToken == null ? "" : preferredNameToken.toLowerCase(Locale.US);
        if (token.isEmpty()) return matches;

        ArrayList<Field> preferred = new ArrayList<>();
        for (Field field : matches) {
            if (field.getName().toLowerCase(Locale.US).contains(token)) {
                preferred.add(field);
            }
        }
        return preferred.isEmpty() ? matches : preferred;
    }

    private static Field findBestExactFieldByType(Class<?> type, Class<?> fieldType, String preferredNameToken) {
        if (type == null || fieldType == null) return null;
        ArrayList<Field> matches = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != fieldType) continue;
                field.setAccessible(true);
                matches.add(field);
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);

        String token = preferredNameToken == null ? "" : preferredNameToken.toLowerCase(Locale.US);
        if (!token.isEmpty()) {
            for (Field field : matches) {
                if (field.getName().toLowerCase(Locale.US).contains(token)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Object readField(Field field, Object target) {
        if (field == null || target == null) return null;
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLoginProxyAuthenticator(Object value) {
        if (value == null) return false;
        if (!java.lang.reflect.Proxy.isProxyClass(value.getClass())) return false;
        try {
            return java.lang.reflect.Proxy.getInvocationHandler(value) instanceof LoginProxyAuthenticatorHandler;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLoginProxySocketFactory(Object value) {
        return value instanceof LoginProxyTlsSocketFactory;
    }

    private static LoginHttpProxyConfig getActiveLoginProxyConfig() {
        Context context = currentApplication();
        if (context == null) return null;
        SpotiPassPrefs.Config config = getCachedConfig(context);
        if (!config.enabled || !config.isLoginProxyMode()) return null;

        String host = trimToEmpty(config.loginProxyHost);
        if (host.isEmpty()) {
            logLoginProxyOnce("configHostMissing", "login proxy mode enabled but proxy host is empty");
            return null;
        }

        String rawPort = trimToEmpty(config.loginProxyPort);
        int port = parsePort(rawPort);
        if (port <= 0) {
            logLoginProxyOnce("configPortInvalid|" + rawPort,
                    "login proxy mode enabled but proxy port is invalid: " + rawPort);
            return null;
        }

        return new LoginHttpProxyConfig(
                host,
                port,
                config.loginProxyTls,
                trimToEmpty(config.loginProxyUsername),
                config.loginProxyPassword
        );
    }

    private static boolean isProxyEndpoint(SocketAddress endpoint, LoginHttpProxyConfig config) {
        if (!(endpoint instanceof InetSocketAddress) || config == null) return false;
        InetSocketAddress inetSocketAddress = (InetSocketAddress) endpoint;
        if (inetSocketAddress.getPort() != config.port) return false;

        String configHost = normalizeHost(config.host);
        String endpointHost = normalizeHost(inetSocketAddress.getHostString());
        if (configHost != null && configHost.equals(endpointHost)) {
            return true;
        }

        InetAddress address = inetSocketAddress.getAddress();
        if (address == null || configHost == null) return false;

        String hostAddress = normalizeHost(address.getHostAddress());
        if (configHost.equals(hostAddress)) {
            return true;
        }

        String canonicalHost = normalizeHost(address.getCanonicalHostName());
        return configHost.equals(canonicalHost);
    }

    private static Socket wrapProxySocketWithTls(Socket rawSocket, LoginHttpProxyConfig config) throws IOException {
        if (rawSocket == null || config == null || !config.useTlsToProxy) return rawSocket;
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(rawSocket, config.host, config.port, true);
        sslSocket.setUseClientMode(true);
        sslSocket.setSoTimeout(rawSocket.getSoTimeout());
        SSLParameters sslParameters = sslSocket.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(sslParameters);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static int parsePort(String rawPort) {
        if (rawPort == null || rawPort.isEmpty()) return -1;
        try {
            int port = Integer.parseInt(rawPort);
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void logLoginProxyOnce(String key, String message) {
        if (key == null || key.isEmpty()) {
            log(message);
            return;
        }
        if (loggedLoginProxyKeys.add(key)) {
            log(message);
        }
    }

    private static void logLoginProxyRuntimeOnce(String key, String zhHans, String english) {
        if (key == null || key.isEmpty()) {
            logRuntime(zhHans, english);
            return;
        }
        if (loggedLoginProxyKeys.add(key)) {
            logRuntime(zhHans, english);
        }
    }

    private static Object getRequestFromResponse(Object response) {
        if (response == null) return null;
        try {
            return XposedHelpers.callMethod(response, "request");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int extractResponseCode(Object response) {
        if (response == null) return -1;
        try {
            Object code = XposedHelpers.callMethod(response, "code");
            if (code instanceof Integer) return (Integer) code;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String extractRequestHostFromResponse(Object response) {
        Object request = getRequestFromResponse(response);
        if (request == null) return null;
        try {
            Object httpUrl = XposedHelpers.callMethod(request, "url");
            if (httpUrl == null) return null;
            Object host = XposedHelpers.callMethod(httpUrl, "host");
            return host instanceof String ? normalizeHost((String) host) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getRequestHeader(Object request, String name) {
        if (request == null || name == null) return null;
        try {
            Object value = XposedHelpers.callMethod(request, "header", name);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static InetAddress[] resolveLoginDnsOverride(String host) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost == null || normalizedHost.isEmpty()) return null;
        if (!isLoginHost(normalizedHost)) return null;

        Context context = currentApplication();
        if (context == null) return null;
        SpotiPassPrefs.Config config = getCachedConfig(context);
        if (!config.enabled || !config.loginDnsOnly) return null;

        Map<String, List<byte[]>> rules = getParsedLoginDnsRules(config.loginDnsRules);
        List<byte[]> bytesList = rules.get(normalizedHost);
        if (bytesList == null || bytesList.isEmpty()) return null;

        ArrayList<InetAddress> result = new ArrayList<>(bytesList.size());
        for (byte[] bytes : bytesList) {
            try {
                result.add(InetAddress.getByAddress(normalizedHost, bytes));
            } catch (UnknownHostException ignored) {
            }
        }
        if (result.isEmpty()) return null;
        if (loggedDnsHosts.add(normalizedHost)) {
            logRuntime(
                    "DNS 覆写 " + normalizedHost + " -> " + result,
                    "DNS override " + normalizedHost + " -> " + result
            );
        }
        return result.toArray(new InetAddress[0]);
    }

    private static Map<String, List<byte[]>> getParsedLoginDnsRules(String rawRules) {
        String safeRaw = rawRules == null ? "" : rawRules;
        if (safeRaw.equals(cachedLoginDnsRulesRaw)) {
            return cachedLoginDnsRules;
        }
        synchronized (SpotiPass.class) {
            if (safeRaw.equals(cachedLoginDnsRulesRaw)) {
                return cachedLoginDnsRules;
            }
            cachedLoginDnsRules = parseLoginDnsRules(safeRaw);
            cachedLoginDnsRulesRaw = safeRaw;
            loggedDnsHosts.clear();
            return cachedLoginDnsRules;
        }
    }

    private static Map<String, List<byte[]>> parseLoginDnsRules(String rawRules) {
        Map<String, List<byte[]>> map = new HashMap<>();
        if (rawRules == null || rawRules.isEmpty()) return map;

        String normalized = rawRules.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;

            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length() - 1) continue;

            String host = normalizeHost(t.substring(0, eq));
            if (host == null || host.isEmpty() || !isLoginHost(host)) continue;
            String rhs = t.substring(eq + 1);
            String[] parts = rhs.split("[,\\s]+");
            for (String p : parts) {
                String ip = p == null ? "" : p.trim();
                if (ip.isEmpty()) continue;
                byte[] addr = parseIpv4Literal(ip);
                if (addr == null) continue;
                map.computeIfAbsent(host, k -> new ArrayList<>()).add(addr);
            }
        }
        return map;
    }

    private static byte[] parseIpv4Literal(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            int v;
            try {
                v = Integer.parseInt(p);
            } catch (Throwable t) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    private static String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.US);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    private static boolean isLoginHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if ("accounts.spotify.com".equals(host)) return true;
        if ("auth-callback.spotify.com".equals(host)) return true;
        if ("partner-accounts.spotify.com".equals(host)) return true;
        if (CHALLENGE_HOST.equals(host)) return true;
        if (RECAPTCHA_NET_HOST.equals(host)) return true;
        if (RECAPTCHA_GOOGLE_HOST.equals(host)) return true;
        if ("www.gstatic.com".equals(host)) return true;
        if (GSTATIC_CN_HOST.equals(host)) return true;
        return host.startsWith("accounts-") && host.endsWith(".spotify.com");
    }

    private static boolean isSpotifyLoginProxyHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if ("accounts.spotify.com".equals(host)) return true;
        if ("auth-callback.spotify.com".equals(host)) return true;
        if ("partner-accounts.spotify.com".equals(host)) return true;
        if (CHALLENGE_HOST.equals(host)) return true;
        return host.startsWith("accounts-") && host.endsWith(".spotify.com");
    }

    private static String maybeRewriteRecaptchaUrlForCurrentConfig(String url) {
        if (url == null || url.isEmpty()) return url;
        Context context = currentApplication();
        if (context == null) return url;
        SpotiPassPrefs.Config config = getCachedConfig(context);
        if (!config.enabled) return url;
        return rewriteRecaptchaUrlIfNeeded(url);
    }

    private static String rewriteRecaptchaUrlIfNeeded(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            URI uri = URI.create(url);
            String host = normalizeHost(uri.getHost());
            String path = uri.getPath();
            if (RECAPTCHA_GOOGLE_HOST.equals(host) && path != null && path.startsWith(RECAPTCHA_PATH_PREFIX)) {
                URI replaced = new URI(
                        uri.getScheme() == null ? "https" : uri.getScheme(),
                        uri.getUserInfo(),
                        RECAPTCHA_NET_HOST,
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        uri.getFragment()
                );
                return replaced.toString();
            }
        } catch (Throwable ignored) {
        }

        String prefix = "https://" + RECAPTCHA_GOOGLE_HOST + RECAPTCHA_PATH_PREFIX;
        if (url.startsWith(prefix)) {
            return "https://" + RECAPTCHA_NET_HOST + RECAPTCHA_PATH_PREFIX + url.substring(prefix.length());
        }
        String prefixHttp = "http://" + RECAPTCHA_GOOGLE_HOST + RECAPTCHA_PATH_PREFIX;
        if (url.startsWith(prefixHttp)) {
            return "http://" + RECAPTCHA_NET_HOST + RECAPTCHA_PATH_PREFIX + url.substring(prefixHttp.length());
        }
        return url;
    }

    private static URLConnection openRewrittenConnection(String rewrittenUrl, java.net.Proxy proxy) {
        if (Boolean.TRUE.equals(recaptchaRewriteGuard.get())) return null;
        try {
            recaptchaRewriteGuard.set(Boolean.TRUE);
            URL target = new URL(rewrittenUrl);
            if (proxy == null) return target.openConnection();
            return target.openConnection(proxy);
        } catch (Throwable t) {
            log("openRewrittenConnection failed: " + t);
            return null;
        } finally {
            recaptchaRewriteGuard.set(Boolean.FALSE);
        }
    }

    private static void logRecaptchaRewriteOnce(String original, String rewritten) {
        String key = original == null ? "" : original;
        int q = key.indexOf('?');
        if (q >= 0) key = key.substring(0, q);
        if (loggedRecaptchaRewritePaths.add(key)) {
            logRuntime(
                    "reCAPTCHA 请求改写 " + original + " -> " + rewritten,
                    "rewrote reCAPTCHA URL " + original + " -> " + rewritten
            );
        }
    }

    private static boolean isSameString(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static SpotiPassPrefs.Config getCachedConfig(Context context) {
        if (context == null) return cachedConfig;
        SpotiPassPrefs.Config config = SpotiPassPrefs.getInstance(context).getConfig();
        cachedConfig = config;
        configLoaded.set(true);
        return config;
    }

    private static Context currentApplication() {
        try {
            Class<?> androidAppHelper = XposedHelpers.findClass(
                    "de.robv.android.xposed.helpers.AndroidAppHelper",
                    SpotiPass.class.getClassLoader()
            );
            Object app = XposedHelpers.callStaticMethod(androidAppHelper, "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
