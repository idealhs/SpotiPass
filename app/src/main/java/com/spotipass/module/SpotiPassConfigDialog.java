package com.spotipass.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class SpotiPassConfigDialog {

    private static final int COLOR_BG = Color.parseColor("#FFFFFF");
    private static final int COLOR_TEXT = Color.parseColor("#212121");
    private static final int COLOR_STATUS_BG = Color.parseColor("#F0F0F0");
    private static final int COLOR_INPUT_BG = Color.parseColor("#FAFAFA");
    private static final int COLOR_INPUT_BORDER = Color.parseColor("#CCCCCC");

    private SpotiPassConfigDialog() {}

    static void show(Activity activity, Runnable onDismissAction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        SpotiPassPrefs prefs = SpotiPassPrefs.getInstance(activity);
        SpotiPassPrefs.Config config = prefs.getConfig();

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int smallPad = (int) (8 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(COLOR_BG);

        TextView statusView = new TextView(activity);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextSize(12f);
        statusView.setTextColor(COLOR_TEXT);
        statusView.setTextIsSelectable(true);
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(COLOR_STATUS_BG);
        statusBg.setCornerRadius(6 * density);
        statusView.setBackground(statusBg);
        statusView.setPadding(smallPad, smallPad, smallPad, smallPad);
        statusView.setText("\u6b63\u5728\u52a0\u8f7d\u72b6\u6001...");
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addSpacer(root, smallPad);

        CheckBox cbEnabled = new CheckBox(activity);
        cbEnabled.setText("\u542f\u7528\u767b\u5f55\u8f85\u52a9");
        cbEnabled.setTextColor(COLOR_TEXT);
        cbEnabled.setChecked(config.enabled);
        root.addView(cbEnabled);

        TextView modeLabel = new TextView(activity);
        modeLabel.setText("\u767b\u5f55\u94fe\u8def\u7b56\u7565");
        modeLabel.setTextColor(COLOR_TEXT);
        root.addView(modeLabel);

        RadioGroup loginModeGroup = new RadioGroup(activity);
        loginModeGroup.setOrientation(LinearLayout.VERTICAL);
        RadioButton rbNone = createModeRadio(activity, "\u65e0\u9644\u52a0\u767b\u5f55\u94fe\u8def\u7b56\u7565");
        RadioButton rbDns = createModeRadio(activity, "\u767b\u5f55 DNS");
        RadioButton rbProxy = createModeRadio(activity, "\u767b\u5f55\u4ee3\u7406");
        loginModeGroup.addView(rbNone);
        loginModeGroup.addView(rbDns);
        loginModeGroup.addView(rbProxy);
        root.addView(loginModeGroup);

        LinearLayout dnsSection = new LinearLayout(activity);
        dnsSection.setOrientation(LinearLayout.VERTICAL);

        TextView dnsLabel = new TextView(activity);
        dnsLabel.setText("\u767b\u5f55 DNS \u89c4\u5219");
        dnsLabel.setTextColor(COLOR_TEXT);
        dnsSection.addView(dnsLabel);

        EditText loginDnsRulesInput = new EditText(activity);
        loginDnsRulesInput.setHint("\u6bcf\u884c\u683c\u5f0f\uff1ahost=ip1,ip2");
        loginDnsRulesInput.setHintTextColor(Color.parseColor("#999999"));
        loginDnsRulesInput.setTextColor(COLOR_TEXT);
        loginDnsRulesInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        loginDnsRulesInput.setMinLines(5);
        loginDnsRulesInput.setMaxLines(10);
        if (config.loginDnsRules == null || config.loginDnsRules.trim().isEmpty()) {
            loginDnsRulesInput.setText(defaultLoginDnsRulesTemplate());
        } else {
            loginDnsRulesInput.setText(config.loginDnsRules);
        }
        GradientDrawable rulesInputBg = new GradientDrawable();
        rulesInputBg.setColor(COLOR_INPUT_BG);
        rulesInputBg.setStroke((int) (1 * density), COLOR_INPUT_BORDER);
        rulesInputBg.setCornerRadius(4 * density);
        loginDnsRulesInput.setBackground(rulesInputBg);
        loginDnsRulesInput.setPadding(smallPad, smallPad, smallPad, smallPad);
        dnsSection.addView(loginDnsRulesInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(dnsSection);

        LinearLayout proxySection = new LinearLayout(activity);
        proxySection.setOrientation(LinearLayout.VERTICAL);

        TextView proxyLabel = new TextView(activity);
        proxyLabel.setText("\u767b\u5f55 HTTP \u4ee3\u7406");
        proxyLabel.setTextColor(COLOR_TEXT);
        proxySection.addView(proxyLabel);

        EditText loginProxyHostInput = createProxyInput(activity,
                "\u4ee3\u7406\u4e3b\u673a\uff0c\u4f8b\u5982 1.2.3.4 \u6216 proxy.example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                density, smallPad);
        loginProxyHostInput.setText(config.loginProxyHost);
        proxySection.addView(loginProxyHostInput);

        EditText loginProxyPortInput = createProxyInput(activity,
                "\u4ee3\u7406\u7aef\u53e3\uff0c\u4f8b\u5982 8080",
                InputType.TYPE_CLASS_NUMBER,
                density, smallPad);
        loginProxyPortInput.setText(config.loginProxyPort);
        proxySection.addView(loginProxyPortInput);

        CheckBox loginProxyTlsBox = new CheckBox(activity);
        loginProxyTlsBox.setText(text("代理服务器使用 TLS", "Use TLS for proxy server"));
        loginProxyTlsBox.setTextColor(COLOR_TEXT);
        loginProxyTlsBox.setChecked(config.loginProxyTls);
        proxySection.addView(loginProxyTlsBox);

        EditText loginProxyUsernameInput = createProxyInput(activity,
                "\u4ee3\u7406\u7528\u6237\u540d\uff0c\u53ef\u7559\u7a7a",
                InputType.TYPE_CLASS_TEXT,
                density, smallPad);
        loginProxyUsernameInput.setText(config.loginProxyUsername);
        proxySection.addView(loginProxyUsernameInput);

        EditText loginProxyPasswordInput = createProxyInput(activity,
                "\u4ee3\u7406\u5bc6\u7801\uff0c\u53ef\u7559\u7a7a",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                density, smallPad);
        loginProxyPasswordInput.setText(config.loginProxyPassword);
        proxySection.addView(loginProxyPasswordInput);

        Button btnTestLoginProxy = createButton(activity, text("测试登录代理", "Test login proxy"), density);
        proxySection.addView(btnTestLoginProxy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(proxySection);

        if (SpotiPassKeys.isLoginDnsMode(config.loginMode)) {
            rbDns.setChecked(true);
        } else if (SpotiPassKeys.isLoginProxyMode(config.loginMode)) {
            rbProxy.setChecked(true);
        } else {
            rbNone.setChecked(true);
        }
        loginModeGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateLoginModeSections(dnsSection, proxySection, checkedId == rbDns.getId(), checkedId == rbProxy.getId()));
        updateLoginModeSections(dnsSection, proxySection, rbDns.isChecked(), rbProxy.isChecked());

        addSpacer(root, smallPad);

        LinearLayout row1 = createButtonRow(activity);
        Button btnSave = createButton(activity, "\u4fdd\u5b58", density);
        Button btnRefreshStatus = createButton(activity, "\u5237\u65b0\u72b6\u6001", density);
        row1.addView(btnSave, createRowButtonParams(density));
        row1.addView(btnRefreshStatus, createRowButtonParams(density));
        root.addView(row1);

        addSpacer(root, smallPad);

        LinearLayout row2 = createButtonRow(activity);
        Button btnViewLog = createButton(activity, "\u67e5\u770b\u8fd0\u884c\u65e5\u5fd7", density);
        Button btnClearLog = createButton(activity, "\u6e05\u7a7a\u65e5\u5fd7", density);
        row2.addView(btnViewLog, createRowButtonParams(density));
        row2.addView(btnClearLog, createRowButtonParams(density));
        root.addView(row2);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("SpotiPass \u767b\u5f55\u6a21\u5f0f")
                .setView(scrollView)
                .setNegativeButton("\u5173\u95ed", null)
                .create();

        btnSave.setOnClickListener(v -> {
            prefs.putConfig(
                    cbEnabled.isChecked(),
                    radioIdToLoginMode(rbNone, rbDns, rbProxy, loginModeGroup.getCheckedRadioButtonId()),
                    loginDnsRulesInput.getText().toString(),
                    loginProxyHostInput.getText().toString(),
                    loginProxyPortInput.getText().toString(),
                    loginProxyTlsBox.isChecked(),
                    loginProxyUsernameInput.getText().toString(),
                    loginProxyPasswordInput.getText().toString()
            );
            Toast.makeText(activity, "\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show();
            refreshStatusAsync(activity, prefs, statusView);
        });

        btnRefreshStatus.setOnClickListener(v -> refreshStatusAsync(activity, prefs, statusView));

        btnViewLog.setOnClickListener(v -> showLog(activity));

        btnTestLoginProxy.setOnClickListener(v -> {
            String loginMode = radioIdToLoginMode(rbNone, rbDns, rbProxy, loginModeGroup.getCheckedRadioButtonId());
            if (!SpotiPassKeys.isLoginProxyMode(loginMode)) {
                Toast.makeText(activity, text("请先切换到登录代理模式", "Switch to login proxy mode first"), Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(activity, text("正在测试登录代理…", "Testing login proxy..."), Toast.LENGTH_SHORT).show();
            btnTestLoginProxy.setEnabled(false);
            LoginProxyConnectivityTester.testAsync(
                    loginProxyHostInput.getText().toString(),
                    loginProxyPortInput.getText().toString(),
                    loginProxyTlsBox.isChecked(),
                    loginProxyUsernameInput.getText().toString(),
                    loginProxyPasswordInput.getText().toString(),
                    result -> activity.runOnUiThread(() -> {
                        btnTestLoginProxy.setEnabled(true);
                        showProxyTestResult(activity, result);
                    })
            );
        });

        btnClearLog.setOnClickListener(v -> {
            SpotiPassRuntimeLog.clear();
            Toast.makeText(activity, "\u65e5\u5fd7\u5df2\u6e05\u7a7a", Toast.LENGTH_SHORT).show();
            refreshStatusAsync(activity, prefs, statusView);
        });

        dialog.setOnDismissListener(d -> {
            if (onDismissAction != null) onDismissAction.run();
        });

        dialog.show();

        try {
            int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
            if (titleId != 0) {
                TextView titleView = dialog.findViewById(titleId);
                if (titleView != null) titleView.setTextColor(COLOR_TEXT);
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#1976D2"));
        } catch (Throwable ignored) {
        }

        refreshStatusAsync(activity, prefs, statusView);
    }

    private static void refreshStatusAsync(Activity activity, SpotiPassPrefs prefs, TextView statusView) {
        new Thread(() -> {
            String status;
            try {
                status = buildStatus(prefs);
            } catch (Throwable t) {
                status = "\u72b6\u6001\u8bfb\u53d6\u5931\u8d25\uff1a" + t.getMessage();
            }
            String finalStatus = status;
            activity.runOnUiThread(() -> statusView.setText(finalStatus));
        }, "spotipass-dialog-status").start();
    }

    private static String buildStatus(SpotiPassPrefs prefs) {
        SpotiPassPrefs.Config config = prefs.getConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("\u767b\u5f55\u8f85\u52a9\uff1a").append(config.enabled ? "\u5df2\u542f\u7528" : "\u672a\u542f\u7528").append('\n');
        sb.append("\u6a21\u5f0f\uff1a").append(loginModeLabel(config.loginMode)).append('\n');
        sb.append("DNS \u89c4\u5219\uff1a").append(countDnsRuleLines(config.loginDnsRules)).append(" \u6761").append('\n');
        sb.append("\u767b\u5f55\u4ee3\u7406\uff1a").append(formatLoginProxyTarget(config)).append('\n');
        sb.append("\u8fd0\u884c\u65e5\u5fd7\u7f13\u5b58\uff1a").append(SpotiPassRuntimeLog.length()).append(" \u5b57\u7b26").append('\n');
        sb.append("\u8bf4\u660e\uff1a\u65e5\u5fd7\u6765\u81ea\u6a21\u5757\u8fd0\u884c\u65f6\u5185\u5b58\uff0c\u4e0d\u4f9d\u8d56 NPatch \u6587\u4ef6\u65e5\u5fd7\u3002");
        return sb.toString();
    }

    private static void showLog(Activity activity) {
        new Thread(() -> {
            String log = SpotiPassRuntimeLog.readTail(96 * 1024);
            if (log == null || log.isEmpty()) {
                log = "\u6682\u65e0\u8fd0\u884c\u65e5\u5fd7\u3002\n\n"
                        + "\u8bf7\u5148\u5728\u76ee\u6807\u5e94\u7528\u5185\u8d70\u4e00\u904d\u767b\u5f55\u6d41\u7a0b\uff0c\u518d\u56de\u5230\u8fd9\u91cc\u67e5\u770b\u3002";
            }
            String finalLog = log;
            activity.runOnUiThread(() -> {
                float d = activity.getResources().getDisplayMetrics().density;
                int p = (int) (12 * d);

                TextView tv = new TextView(activity);
                tv.setText(finalLog);
                tv.setTextIsSelectable(true);
                tv.setTextSize(11f);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(COLOR_TEXT);
                tv.setBackgroundColor(COLOR_BG);
                tv.setPadding(p, p, p, p);

                ScrollView sv = new ScrollView(activity);
                sv.setBackgroundColor(COLOR_BG);
                sv.addView(tv);

                new AlertDialog.Builder(activity)
                        .setTitle("\u8fd0\u884c\u65e5\u5fd7")
                        .setView(sv)
                        .setPositiveButton("\u5173\u95ed", null)
                        .show();
            });
        }, "spotipass-dialog-log").start();
    }

    private static void showProxyTestResult(Activity activity, LoginProxyConnectivityTester.Result result) {
        if (activity == null || result == null) return;
        float d = activity.getResources().getDisplayMetrics().density;
        int p = (int) (12 * d);

        TextView tv = new TextView(activity);
        tv.setText(result.details);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(COLOR_TEXT);
        tv.setBackgroundColor(COLOR_BG);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(activity);
        sv.setBackgroundColor(COLOR_BG);
        sv.addView(tv);

        new AlertDialog.Builder(activity)
                .setTitle(result.success
                        ? text("登录代理测试成功", "Login proxy test succeeded")
                        : text("登录代理测试失败", "Login proxy test failed"))
                .setMessage(result.summary)
                .setView(sv)
                .setPositiveButton("\u5173\u95ed", null)
                .show();
    }

    private static String text(String zhHans, String english) {
        return SpotiPassI18n.text(zhHans, english);
    }

    private static LinearLayout createButtonRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        return row;
    }

    private static Button createButton(Activity activity, String text, float density) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextSize(13f);
        btn.setTextColor(COLOR_TEXT);
        btn.setAllCaps(false);
        return btn;
    }

    private static LinearLayout.LayoutParams createRowButtonParams(float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int margin = (int) (4 * density);
        lp.setMargins(margin, 0, margin, 0);
        return lp;
    }

    private static void addSpacer(LinearLayout parent, int heightPx) {
        android.view.View spacer = new android.view.View(parent.getContext());
        parent.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
    }

    private static RadioButton createModeRadio(Activity activity, String text) {
        RadioButton radio = new RadioButton(activity);
        radio.setId(android.view.View.generateViewId());
        radio.setText(text);
        radio.setTextColor(COLOR_TEXT);
        return radio;
    }

    private static EditText createProxyInput(Activity activity, String hint, int inputType, float density, int smallPad) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setHintTextColor(Color.parseColor("#999999"));
        input.setTextColor(COLOR_TEXT);
        input.setInputType(inputType);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(COLOR_INPUT_BG);
        inputBg.setStroke((int) (1 * density), COLOR_INPUT_BORDER);
        inputBg.setCornerRadius(4 * density);
        input.setBackground(inputBg);
        input.setPadding(smallPad, smallPad, smallPad, smallPad);
        return input;
    }

    private static void updateLoginModeSections(LinearLayout dnsSection, LinearLayout proxySection, boolean showDns, boolean showProxy) {
        dnsSection.setVisibility(showDns ? LinearLayout.VISIBLE : LinearLayout.GONE);
        proxySection.setVisibility(showProxy ? LinearLayout.VISIBLE : LinearLayout.GONE);
    }

    private static String radioIdToLoginMode(RadioButton rbNone, RadioButton rbDns, RadioButton rbProxy, int checkedId) {
        if (checkedId == rbDns.getId()) return SpotiPassKeys.LOGIN_MODE_DNS;
        if (checkedId == rbProxy.getId()) return SpotiPassKeys.LOGIN_MODE_PROXY;
        return SpotiPassKeys.LOGIN_MODE_NONE;
    }

    private static String loginModeLabel(String loginMode) {
        if (SpotiPassKeys.isLoginDnsMode(loginMode)) return "\u767b\u5f55 DNS";
        if (SpotiPassKeys.isLoginProxyMode(loginMode)) return "\u767b\u5f55\u4ee3\u7406";
        return "\u65e0";
    }

    private static String formatLoginProxyTarget(SpotiPassPrefs.Config config) {
        String host = config.loginProxyHost == null ? "" : config.loginProxyHost.trim();
        String port = config.loginProxyPort == null ? "" : config.loginProxyPort.trim();
        if (host.isEmpty() || port.isEmpty()) return "\u672a\u914d\u7f6e";
        String suffix = hasProxyCredentials(config) ? "\uff08\u542b\u8ba4\u8bc1\uff09" : "";
        String scheme = config.loginProxyTls ? "https://" : "http://";
        return scheme + host + ":" + port + suffix;
    }

    private static boolean hasProxyCredentials(SpotiPassPrefs.Config config) {
        String username = config.loginProxyUsername == null ? "" : config.loginProxyUsername.trim();
        String password = config.loginProxyPassword == null ? "" : config.loginProxyPassword;
        return !username.isEmpty() || !password.isEmpty();
    }

    private static int countDnsRuleLines(String rules) {
        if (rules == null || rules.isEmpty()) return 0;
        String normalized = rules.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int count = 0;
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.contains("=")) count++;
        }
        return count;
    }

    private static String defaultLoginDnsRulesTemplate() {
        return "accounts.spotify.com=35.186.224.24\n"
                + "challenge.spotify.com=35.186.224.24\n"
                + "auth-callback.spotify.com=35.186.224.24\n"
                + "partner-accounts.spotify.com=35.186.224.24\n"
                + "accounts-gew4.spotify.com=35.186.224.28\n"
                + "accounts-gue1.spotify.com=35.186.224.9\n"
                + "accounts-gew1.spotify.com=35.186.224.26\n"
                + "accounts-guc3.spotify.com=35.186.224.31\n"
                + "accounts-gae2.spotify.com=35.186.224.22\n"
                + "www.recaptcha.net=120.253.255.98,120.253.250.226\n"
                + "www.gstatic.com=120.253.253.226,120.253.253.162,120.253.255.34\n"
                + "www.gstatic.cn=120.253.253.226,120.253.253.162,120.253.255.34\n";
    }
}
