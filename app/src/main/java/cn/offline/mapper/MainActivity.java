package cn.offline.mapper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity implements RootBridge.Listener {
    private static final int TAB_GAMES = 0, TAB_PROFILES = 1, TAB_SETTINGS = 2;
    private static final int REQUEST_IMPORT = 11, REQUEST_EXPORT = 12;
    private RootBridge bridge;
    private int tab = TAB_GAMES;
    private boolean alive;
    private String exportSnapshot, renderedState;
    private LinearLayout content;
    private FrameLayout chipSlot;
    private TextView heroStatus, heroDevices;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        public void run() { if (alive) { bridge.refresh(); handler.postDelayed(this, 2000); } }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { exportSnapshot = state.getString("exportSnapshot"); tab = state.getInt("tab", TAB_GAMES); }
        tab = Math.max(0, Math.min(2, getIntent().getIntExtra("tab", tab)));
        bridge = RootBridge.get(this);
        bridge.addListener(this);
        render();
        String open = getIntent().getStringExtra("open");
        if ("editor".equals(open)) showOverlay(true);
        else if ("lab".equals(open)) startActivity(new Intent(this, TouchLabActivity.class));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("exportSnapshot", exportSnapshot); state.putInt("tab", tab);
    }
    @Override protected void onResume() {
        super.onResume(); alive = true;
        if (bridge.running()) bridge.pauseMapping();
        render();
        handler.removeCallbacks(poll); handler.post(poll);
    }
    @Override protected void onPause() { super.onPause(); alive = false; handler.removeCallbacks(poll); }
    @Override protected void onDestroy() { bridge.removeListener(this); super.onDestroy(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); render(); }

    private int dp(float n) { return Ui.dp(this, n); }
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException impossible) { return ""; }
    }
    private String stateKey() { return bridge.connected() + "/" + bridge.running() + "/" + bridge.activating(); }

    // ---------- Shell ----------

    private void render() {
        renderedState = stateKey();
        heroStatus = null; heroDevices = null;
        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.BG);
        setContentView(page);

        LinearLayout header = Ui.row(this);
        header.setPadding(dp(20), dp(18), dp(20), dp(6));
        LinearLayout titles = Ui.column(this);
        String[] names = {"游戏", "配置", "设置"};
        titles.addView(Ui.title(this, names[tab], 28));
        titles.addView(Ui.text(this, "键鼠映射 · Root 本地版 " + version(), 12, Ui.MUTED));
        header.addView(titles, Ui.weight(1));
        chipSlot = new FrameLayout(this);
        header.addView(chipSlot, Ui.wrap());
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(this);
        content.setPadding(dp(16), dp(10), dp(16), dp(24));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(navigation());

        if (tab == TAB_GAMES) games(); else if (tab == TAB_PROFILES) profiles(); else settings();
        refreshStatus();
    }

    private View navigation() {
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(dp(12), dp(8), dp(12), dp(10));
        int[] icons = {Icon.GAMEPAD, Icon.LAYERS, Icon.SETTINGS};
        String[] labels = {"游戏", "配置", "设置"};
        for (int i = 0; i < 3; i++) {
            final int target = i; boolean active = i == tab;
            LinearLayout item = Ui.column(this); item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(2));
            item.setBackground(Ui.pressable(this, android.graphics.Color.TRANSPARENT, 16, 0));
            FrameLayout pill = new FrameLayout(this);
            pill.setBackground(Ui.shape(this, active ? Ui.alpha(Ui.ACCENT, 40) : android.graphics.Color.TRANSPARENT, 999, 0));
            ImageView icon = new ImageView(this); icon.setImageDrawable(new Icon(this, icons[i], active ? Ui.ACCENT : Ui.MUTED));
            pill.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            item.addView(pill, new LinearLayout.LayoutParams(dp(60), dp(32)));
            TextView label = Ui.text(this, labels[i], 12, active ? Ui.TEXT : Ui.MUTED);
            if (active) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            item.addView(label, Ui.margins(this, Ui.wrap(), 0, 4, 0, 0));
            item.setContentDescription(labels[i]);
            item.setOnClickListener(v -> { if (tab != target) { tab = target; render(); } });
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return nav;
    }

    private void section(String title, String actionLabel, Runnable action) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(4), dp(18), dp(4), dp(8));
        r.addView(Ui.overline(this, title), Ui.weight(1));
        if (actionLabel != null) {
            TextView a = Ui.text(this, actionLabel, 13, Ui.ACCENT);
            a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            a.setPadding(dp(8), dp(4), dp(4), dp(4));
            a.setOnClickListener(v -> action.run());
            r.addView(a);
        }
        content.addView(r);
    }

    private void add(View v) { content.addView(v, Ui.fill(-2)); }
    private void gap(int height) { content.addView(new View(this), Ui.fill(dp(height))); }

    private void refreshStatus() {
        if (chipSlot != null) {
            chipSlot.removeAllViews();
            String label; int color;
            if (bridge.running()) { label = "映射中"; color = Ui.SUCCESS; }
            else if (bridge.connected()) { label = "已就绪"; color = Ui.ACCENT; }
            else if (bridge.activating()) { label = "激活中"; color = Ui.WARN; }
            else { label = "未激活"; color = Ui.MUTED; }
            chipSlot.addView(Ui.statusChip(this, label, color));
        }
        if (heroStatus != null) heroStatus.setText(bridge.status());
        if (heroDevices != null) heroDevices.setText(InputDiagnosticsStore.deviceSummary(bridge.detail(), InputDiagnosticsStore.systemDevices(), bridge.connected()));
    }

    @Override public void onChanged() {
        if (!alive) return;
        if (!stateKey().equals(renderedState)) render(); else refreshStatus();
    }

    // ---------- Games ----------

    private void games() {
        LinearLayout hero = Ui.column(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xff22356b, 0xff151a26});
        bg.setCornerRadius(dp(24)); bg.setStroke(Math.max(1, dp(1)), 0xff2c3a5e);
        hero.setBackground(bg);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.iconTile(this, bridge.connected() ? Icon.SHIELD : Icon.POWER, bridge.connected() ? Ui.SUCCESS : Ui.ACCENT, 44),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.overline(this, "服务状态"));
        heroStatus = Ui.title(this, bridge.status(), 17);
        texts.addView(heroStatus, Ui.margins(this, Ui.wrap(), 0, 2, 0, 0));
        top.addView(texts, Ui.margins(this, Ui.weight(1), 14, 0, 0, 0));
        hero.addView(top);
        heroDevices = Ui.text(this, "", 13, 0xffaab3c5);
        hero.addView(heroDevices, Ui.margins(this, Ui.fill(-2), 0, 14, 0, 0));
        LinearLayout buttons = Ui.row(this);
        if (bridge.connected()) buttons.addView(Ui.button(this, "打开悬浮球", Icon.WINDOW, Ui.Kind.PRIMARY, () -> showOverlay(false)), Ui.weight(1));
        else buttons.addView(Ui.button(this, bridge.activating() ? "正在激活…" : "激活 Root 服务", Icon.POWER, Ui.Kind.PRIMARY, bridge::activate), Ui.weight(1));
        buttons.addView(Ui.button(this, "练习场", Icon.TARGET, Ui.Kind.SECONDARY,
                () -> startActivity(new Intent(this, TouchLabActivity.class))), Ui.margins(this, Ui.wrap(), 10, 0, 0, 0));
        hero.addView(buttons, Ui.margins(this, Ui.fill(-2), 0, 16, 0, 0));
        add(hero);

        section("当前配置", "管理", () -> { tab = TAB_PROFILES; render(); });
        JSONObject p = ProfileStore.current(this);
        LinearLayout edit = Ui.button(this, "编辑", Icon.EDIT, Ui.Kind.SECONDARY, () -> showOverlay(true));
        add(Ui.group(this, Ui.listRow(this, Icon.LAYERS, Ui.ACCENT, p.optString("name"), summary(p), edit, null)));

        section("我的游戏", "＋ 添加", this::chooseGame);
        JSONArray all = ProfileStore.all(this);
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) { JSONObject item = all.optJSONObject(i); if (item != null && !item.optString("package").isEmpty()) list.add(item); }
        if (list.isEmpty()) {
            LinearLayout empty = Ui.card(this); empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(20), dp(28), dp(20), dp(24));
            empty.addView(Ui.iconTile(this, Icon.GAMEPAD, Ui.ACCENT, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView t = Ui.title(this, "还没有添加游戏", 16); t.setGravity(Gravity.CENTER);
            empty.addView(t, Ui.margins(this, Ui.wrap(), 0, 14, 0, 0));
            TextView s = Ui.text(this, "为每个游戏保存独立的按键布局，全部保存在本机。", 13, Ui.MUTED); s.setGravity(Gravity.CENTER);
            empty.addView(s, Ui.margins(this, Ui.wrap(), 0, 4, 0, 16));
            empty.addView(Ui.button(this, "添加已安装游戏", Icon.PLUS, Ui.Kind.PRIMARY, this::chooseGame), Ui.wrap());
            add(empty);
        } else {
            LinearLayout line = null;
            for (int i = 0; i < list.size(); i++) {
                if (i % 2 == 0) { line = Ui.row(this); line.setGravity(Gravity.TOP); content.addView(line, Ui.margins(this, Ui.fill(-2), 0, i == 0 ? 0 : 10, 0, 0)); }
                line.addView(gameCard(list.get(i)), Ui.margins(this, new LinearLayout.LayoutParams(0, -2, 1), i % 2 == 0 ? 0 : 5, 0, i % 2 == 0 ? 5 : 0, 0));
            }
            if (list.size() % 2 == 1) line.addView(new View(this), Ui.margins(this, new LinearLayout.LayoutParams(0, 1, 1), 5, 0, 0, 0));
        }

        section("快捷键", null, null);
        LinearLayout keys = Ui.card(this);
        keys.addView(shortcut("F1", "切换自由鼠标 / 锁定视角"));
        keys.addView(shortcut("F8", "紧急暂停并释放键鼠"), Ui.margins(this, Ui.fill(-2), 0, 12, 0, 0));
        keys.addView(shortcut("WASD", "方向摇杆（可在编辑器中修改）"), Ui.margins(this, Ui.fill(-2), 0, 12, 0, 0));
        add(keys);
    }

    private String summary(JSONObject p) {
        JSONArray b = p.optJSONArray("bindings");
        StringBuilder s = new StringBuilder((b == null ? 0 : b.length()) + " 个按键");
        JSONObject j = p.optJSONObject("joystick"), a = p.optJSONObject("aim");
        if (j != null && j.optBoolean("enabled")) s.append(" · 摇杆");
        if (a != null && a.optBoolean("enabled")) s.append(" · 鼠标视角");
        return s.toString();
    }

    private View shortcut(String key, String description) {
        LinearLayout r = Ui.row(this);
        TextView cap = Ui.text(this, key, 13, Ui.TEXT);
        cap.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cap.setGravity(Gravity.CENTER);
        cap.setBackground(Ui.shape(this, Ui.SURFACE_3, 8, 0xff323a4a));
        cap.setPadding(dp(10), dp(5), dp(10), dp(5));
        cap.setMinWidth(dp(56));
        r.addView(cap, Ui.wrap());
        r.addView(Ui.text(this, description, 14, Ui.MUTED), Ui.margins(this, Ui.weight(1), 14, 0, 0, 0));
        return r;
    }

    private Drawable appIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException gone) { return null; }
    }

    private View gameCard(JSONObject profile) {
        String id = profile.optString("id");
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.pressable(this, Ui.SURFACE, 20, Ui.STROKE));
        card.setPadding(dp(16), dp(16), dp(16), dp(14));
        card.setClickable(true);
        card.setOnClickListener(v -> launchGame(id));
        card.setOnLongClickListener(v -> { profileMenu(profile); return true; });
        Drawable icon = appIcon(profile.optString("package"));
        if (icon != null) {
            ImageView image = new ImageView(this); image.setImageDrawable(icon);
            card.addView(image, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else card.addView(Ui.iconTile(this, Icon.GAMEPAD, Ui.MUTED, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = Ui.title(this, profile.optString("name"), 15); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, Ui.margins(this, Ui.fill(-2), 0, 12, 0, 0));
        TextView sub = Ui.text(this, summary(profile), 12, Ui.MUTED); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(sub, Ui.margins(this, Ui.fill(-2), 0, 2, 0, 12));
        LinearLayout play = Ui.row(this);
        play.addView(Ui.chip(this, icon == null ? "未安装" : "点击启动", icon == null ? Ui.DANGER : Ui.ACCENT));
        card.addView(play);
        return card;
    }

    // ---------- Profiles ----------

    private void profiles() {
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "新建配置", Icon.PLUS, Ui.Kind.PRIMARY,
                () -> Sheet.name(this, "新建配置", "", name -> { saveProfile(ProfileStore.template(name, "")); render(); })), Ui.weight(1));
        actions.addView(Ui.button(this, "导入", Icon.IMPORT, Ui.Kind.SECONDARY, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            startActivityForResult(i, REQUEST_IMPORT);
        }), Ui.margins(this, Ui.weight(1), 10, 0, 0, 0));
        add(actions);
        TextView hint = Ui.text(this, "按游戏保存点位，坐标按屏幕比例保存。导出 JSON 后可在另一台设备导入。", 13, Ui.MUTED);
        content.addView(hint, Ui.margins(this, Ui.fill(-2), 4, 12, 4, 0));

        section("全部配置", null, null);
        JSONArray a = ProfileStore.all(this);
        String current = ProfileStore.current(this).optString("id");
        View[] rows = new View[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            boolean selected = p.optString("id").equals(current);
            LinearLayout trailing = Ui.row(this);
            if (selected) trailing.addView(Ui.chip(this, "当前", Ui.SUCCESS), Ui.margins(this, Ui.wrap(), 0, 0, 8, 0));
            ImageView more = new ImageView(this); more.setImageDrawable(new Icon(this, Icon.MORE, Ui.MUTED));
            trailing.addView(more, new LinearLayout.LayoutParams(dp(20), dp(20)));
            String pkg = p.optString("package");
            String sub = summary(p) + " · " + (pkg.isEmpty() ? "通用布局" : pkg);
            rows[i] = Ui.listRow(this, pkg.isEmpty() ? Icon.LAYERS : Icon.GAMEPAD, selected ? Ui.SUCCESS : Ui.ACCENT, p.optString("name"), sub, trailing, () -> profileMenu(p));
        }
        add(Ui.group(this, rows));
    }

    private void profileMenu(JSONObject p) {
        String id = p.optString("id");
        new Sheet(this, p.optString("name"), summary(p), false)
            .item(Icon.CHECK, Ui.SUCCESS, "设为当前配置", null, () -> { bridge.pauseMapping(); ProfileStore.select(this, id); render(); })
            .item(Icon.EDIT, Ui.ACCENT, "在屏幕上编辑", "打开悬浮编辑器，拖动点位", () -> { bridge.pauseMapping(); ProfileStore.select(this, id); showOverlay(true); })
            .item(Icon.EDIT, Ui.ACCENT, "重命名", null, () -> Sheet.name(this, "重命名", p.optString("name"), name -> { p.put("name", name); saveProfile(p); render(); }))
            .item(Icon.COPY, Ui.ACCENT, "复制配置", null, () -> {
                JSONObject copy = ProfileStore.copy(p); copy.put("id", UUID.randomUUID().toString()).put("name", p.optString("name") + " 副本");
                saveProfile(copy); render();
            })
            .item(Icon.EXPORT, Ui.ACCENT, "导出 JSON", null, () -> {
                exportSnapshot = p.toString();
                Intent file = new Intent(Intent.ACTION_CREATE_DOCUMENT); file.addCategory(Intent.CATEGORY_OPENABLE); file.setType("application/json");
                file.putExtra(Intent.EXTRA_TITLE, "映射-" + p.optString("name") + ".json");
                startActivityForResult(file, REQUEST_EXPORT);
            })
            .item(Icon.TRASH, Ui.DANGER, "删除配置", null, () -> Sheet.confirm(this, "删除这个配置？", p.optString("name") + "\n删除后无法恢复。", "删除",
                    () -> { bridge.pauseMapping(); ProfileStore.delete(this, id); render(); }))
            .show();
    }

    private void saveProfile(JSONObject p) { bridge.pauseMapping(); ProfileStore.save(this, p); }

    // ---------- Settings ----------

    private void settings() {
        section("服务", null, null);
        View service = bridge.connected()
            ? Ui.button(this, "停止", Icon.STOP, Ui.Kind.DANGER, () -> { bridge.stop(); stopService(new Intent(this, OverlayService.class)); render(); })
            : Ui.button(this, "激活", Icon.POWER, Ui.Kind.PRIMARY, bridge::activate);
        LinearLayout serviceRow = Ui.listRow(this, Icon.SHIELD, bridge.connected() ? Ui.SUCCESS : Ui.MUTED, "Root 服务", bridge.status(), service, null);
        add(Ui.group(this, serviceRow,
            Ui.listRow(this, Icon.PULSE, Ui.ACCENT, "本地诊断", "查看设备扫描结果，复制日志", Ui.chevron(this), this::showDiagnostics)));
        TextView note = Ui.text(this, "诊断保留上次启动失败时的设备清单，不记录打字内容。", 12, Ui.FAINT);
        content.addView(note, Ui.margins(this, Ui.fill(-2), 6, 8, 6, 0));

        section("权限", null, null);
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notify = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        add(Ui.group(this,
            Ui.listRow(this, Icon.WINDOW, Ui.ACCENT, "悬浮窗", "悬浮球和游戏内编辑需要", Ui.chip(this, overlay ? "已允许" : "去开启", overlay ? Ui.SUCCESS : Ui.WARN),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))),
            Ui.listRow(this, Icon.BELL, Ui.ACCENT, "通知", "显示运行状态与快捷暂停", Ui.chip(this, notify ? "已允许" : "去开启", notify ? Ui.SUCCESS : Ui.WARN),
                () -> { if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 40); })));

        section("使用步骤", null, null);
        LinearLayout steps = Ui.card(this);
        String[] list = {"在 Magisk / KernelSU / APatch 中允许本应用 Root", "允许悬浮窗，连接 USB 或蓝牙键鼠", "添加游戏，进入游戏后用悬浮球编辑布局",
                "保存后开始映射，F1 切换视角，F8 随时暂停", "退出悬浮球时会停止服务，键鼠恢复正常"};
        for (int i = 0; i < list.length; i++) {
            LinearLayout r = Ui.row(this); r.setGravity(Gravity.TOP);
            TextView n = Ui.text(this, String.valueOf(i + 1), 12, Ui.ACCENT); n.setGravity(Gravity.CENTER);
            n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            n.setBackground(Ui.shape(this, Ui.alpha(Ui.ACCENT, 34), 999, 0));
            r.addView(n, new LinearLayout.LayoutParams(dp(22), dp(22)));
            r.addView(Ui.text(this, list[i], 14, Ui.TEXT), Ui.margins(this, Ui.weight(1), 12, 1, 0, 0));
            steps.addView(r, Ui.margins(this, Ui.fill(-2), 0, i == 0 ? 0 : 12, 0, 0));
        }
        add(steps);

        section("关于", null, null);
        add(Ui.group(this,
            Ui.listRow(this, Icon.INFO, Ui.MUTED, "版本 " + version(), "完全本地运行，无需账号、联网或激活码", null, null),
            Ui.listRow(this, Icon.SHIELD, Ui.MUTED, "数据只保存在本机", "开源独立实现", null, null)));
    }

    private void showDiagnostics() {
        Sheet sheet = new Sheet(this, "本地诊断", null, false);
        TextView t = Ui.text(this, bridge.diagnostics(), 12, Ui.MUTED);
        t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true);
        t.setBackground(Ui.shape(this, Ui.SURFACE_2, 12, 0)); t.setPadding(dp(12), dp(10), dp(12), dp(10));
        sheet.body.addView(t, Ui.fill(-2));
        sheet.action("关闭", Ui.Kind.GHOST, null).action("复制", Ui.Kind.PRIMARY, () -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("映射诊断", bridge.diagnostics()));
            toast("已复制");
        });
        sheet.show();
    }

    // ---------- Actions ----------

    private void showOverlay(boolean editor) {
        if (!Settings.canDrawOverlays(this)) {
            Sheet sheet = new Sheet(this, "允许悬浮窗", "游戏内编辑和悬浮球需要这个权限。允许后回到这里再试一次。", false);
            sheet.action("取消", Ui.Kind.GHOST, null).action("去设置", Ui.Kind.PRIMARY,
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
            sheet.show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 40);
        Intent intent = new Intent(this, OverlayService.class);
        if (editor) intent.setAction(OverlayService.ACTION_EDITOR);
        startForegroundService(intent);
    }

    private void launchGame(String id) {
        if (bridge.running()) bridge.pauseMapping();
        ProfileStore.select(this, id);
        JSONObject p = ProfileStore.current(this);
        Intent game = getPackageManager().getLaunchIntentForPackage(p.optString("package"));
        if (game == null) { toast("游戏已卸载或无法启动"); return; }
        if (!Settings.canDrawOverlays(this)) { showOverlay(false); return; }
        showOverlay(false); startActivity(game);
        toast("进入游戏后点击悬浮球编辑或开始映射");
    }

    private static final class App { final String label, pkg; final Drawable icon; App(String l, String p, Drawable i) { label = l; pkg = p; icon = i; } }

    private void chooseGame() {
        Sheet sheet = new Sheet(this, "添加游戏", "选择一个已安装的应用，为它创建独立配置", false);
        EditText search = Sheet.field(sheet.context(), "", "搜索应用");
        sheet.body.addView(search, Ui.fill(dp(48)));
        LinearLayout list = Ui.column(sheet.context());
        sheet.body.addView(list, Ui.margins(this, Ui.fill(-2), 0, 8, 0, 0));
        TextView loading = Ui.text(this, "正在读取已安装应用…", 13, Ui.MUTED);
        list.addView(loading, Ui.margins(this, Ui.wrap(), 4, 12, 0, 12));
        sheet.action("取消", Ui.Kind.GHOST, null);
        sheet.show();
        new Thread(() -> {
            Intent launcher = new Intent(Intent.ACTION_MAIN); launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = getPackageManager().queryIntentActivities(launcher, 0);
            List<App> apps = new ArrayList<>();
            for (ResolveInfo r : infos) {
                if (r.activityInfo.packageName.equals(getPackageName())) continue;
                apps.add(new App(r.loadLabel(getPackageManager()).toString(), r.activityInfo.packageName, r.loadIcon(getPackageManager())));
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (isFinishing() || !sheet.dialog.isShowing()) return;
                Runnable fill = () -> {
                    list.removeAllViews();
                    String q = search.getText().toString().trim().toLowerCase();
                    int shown = 0;
                    for (App app : apps) {
                        if (!q.isEmpty() && !app.label.toLowerCase().contains(q) && !app.pkg.toLowerCase().contains(q)) continue;
                        if (++shown > 200) break;
                        list.addView(appRow(app, () -> {
                            sheet.dialog.dismiss();
                            try { saveProfile(ProfileStore.template(app.label, app.pkg)); tab = TAB_GAMES; render(); }
                            catch (RuntimeException problem) { toast(problem.getMessage()); }
                        }));
                    }
                    if (shown == 0) list.addView(Ui.text(this, "没有匹配的应用", 13, Ui.MUTED), Ui.margins(this, Ui.wrap(), 4, 12, 0, 12));
                };
                fill.run();
                search.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    public void afterTextChanged(Editable s) { fill.run(); }
                });
            });
        }, "game-list").start();
    }

    private View appRow(App app, Runnable action) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.setBackground(Ui.pressable(this, android.graphics.Color.TRANSPARENT, 14, 0));
        r.setOnClickListener(v -> action.run());
        ImageView icon = new ImageView(this); icon.setImageDrawable(app.icon);
        r.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout t = Ui.column(this);
        TextView name = Ui.title(this, app.label, 15); name.setSingleLine(true); t.addView(name);
        TextView pkg = Ui.text(this, app.pkg, 12, Ui.FAINT); pkg.setSingleLine(true); pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); t.addView(pkg);
        r.addView(t, Ui.margins(this, Ui.weight(1), 12, 0, 0, 0));
        return r;
    }

    private void toast(String s) { Toast.makeText(this, s == null ? "操作失败" : s, Toast.LENGTH_LONG).show(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == REQUEST_IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(data.getData()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 262144) throw new IOException("配置文件不得超过256KB"); }
                    JSONObject p = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    p.put("id", UUID.randomUUID().toString());
                    ProfileStore.validate(p);
                    saveProfile(p); tab = TAB_PROFILES; render(); toast("配置导入完成");
                }
            } else if (request == REQUEST_EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (exportSnapshot == null) throw new IOException("导出已中断，请重新选择配置");
                    out.write(new JSONObject(exportSnapshot).toString(2).getBytes(StandardCharsets.UTF_8));
                    exportSnapshot = null; toast("配置已导出");
                }
            }
        } catch (IOException | JSONException | RuntimeException e) { toast("文件操作失败：" + e.getMessage()); }
    }
}
