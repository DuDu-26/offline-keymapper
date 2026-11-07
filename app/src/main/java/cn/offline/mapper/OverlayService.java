package cn.offline.mapper;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONException;
import org.json.JSONObject;

/** Small non-focusable runtime overlay plus a temporary full-screen editor. */
public final class OverlayService extends Service implements RootBridge.Listener {
    public static final String ACTION_EDITOR = "cn.offline.mapper.EDIT";
    public static final String ACTION_START = "cn.offline.mapper.START";
    public static final String ACTION_STOP = "cn.offline.mapper.STOP";
    private static final String ACTION_PAUSE = "cn.offline.mapper.PAUSE";
    private static final String CHANNEL = "mapping-session";
    private static final int NOTIFICATION_ID = 7;
    private static final int ACCENT = Color.rgb(68, 214, 44);
    private WindowManager manager;
    private RootBridge bridge;
    private TextView ball, selectionText;
    private WindowManager.LayoutParams ballParams;
    private FrameLayout editor;
    private MappingCanvas canvas;
    private JSONObject working;
    private AlertDialog dialog;
    private boolean destroyed;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorParams;
    private boolean lastNotifiedRunning;
    private boolean ballAppearanceReady, ballRenderedRunning;
    private float ballFractionX = .94f, ballFractionY = .28f;

    @Override public void onCreate() {
        super.onCreate();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        bridge = RootBridge.get(this);
        bridge.addListener(this);
        ballFractionX = getSharedPreferences("overlay", MODE_PRIVATE).getFloat("x", .94f);
        ballFractionY = getSharedPreferences("overlay", MODE_PRIVATE).getFloat("y", .28f);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "键鼠映射运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("悬浮球和离线映射服务"); nm.createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) { toast("请先允许“显示在其他应用上层”"); stopSelf(); return START_NOT_STICKY; }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { bridge.stop(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_EDITOR.equals(action)) openEditor();
        else if (ACTION_START.equals(action)) startMapping();
        else if (ACTION_PAUSE.equals(action)) { bridge.pauseMapping(); showBall(); }
        else showBall();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private Notification notification() {
        Intent home = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, home, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = bridge == null ? "点击悬浮球设置；F8 紧急暂停" : (bridge.running() ? "映射正在运行 · F8 紧急暂停" : "映射已暂停 · 点击悬浮球编辑或开始");
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("离线键鼠映射").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "暂停", pause).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "退出", stop).build()).build();
    }
    @Override public void onChanged() {
        // RootBridge already dispatches status listeners on the main thread.
        if (destroyed) return;
        refreshBall(); refreshCursor();
        if (lastNotifiedRunning != bridge.running()) {
            lastNotifiedRunning = bridge.running();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    }
    @Override public void onCursorChanged() {
        // One call per Choreographer frame, with the latest position. No ball
        // repaint, notification work, or extra Handler hop on cursor packets.
        if (!destroyed) refreshCursor();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Point screen() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = manager.getMaximumWindowMetrics().getBounds(); return new Point(bounds.width(), bounds.height());
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(metrics); return new Point(metrics.widthPixels, metrics.heightPixels);
    }
    private GradientDrawable background(int color, int stroke, float radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius));
        if (stroke != 0) result.setStroke(dp(1), stroke); return result;
    }
    private void showBall() {
        if (editor != null || ball != null || destroyed) return;
        ball = new TextView(this); ball.setGravity(Gravity.CENTER); ball.setTextSize(12); ball.setElevation(dp(8)); ball.setContentDescription("离线键鼠映射悬浮球，点击菜单，拖动调整位置");
        ballAppearanceReady = false;
        ballParams = new WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) ballParams.setFitInsetsTypes(0);
        positionBall();
        final float[] touch = new float[4]; final boolean[] moved = new boolean[1];
        ball.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touch[0] = event.getRawX(); touch[1] = event.getRawY(); touch[2] = ballParams.x; touch[3] = ballParams.y; moved[0] = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touch[0], dy = event.getRawY() - touch[1];
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) moved[0] = true;
                    if (moved[0]) {
                        Point size = screen(); ballParams.x = Math.max(0, Math.min(size.x - dp(52), Math.round(touch[2] + dx)));
                        ballParams.y = Math.max(0, Math.min(size.y - dp(52), Math.round(touch[3] + dy)));
                        try { manager.updateViewLayout(ball, ballParams); } catch (IllegalArgumentException ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) view.performClick(); else {
                        Point size = screen(); ballFractionX = (ballParams.x + dp(26f)) / (float) size.x; ballFractionY = (ballParams.y + dp(26f)) / (float) size.y;
                        getSharedPreferences("overlay", MODE_PRIVATE).edit().putFloat("x", ballFractionX).putFloat("y", ballFractionY).apply();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
        });
        ball.setOnClickListener(view -> showMenu()); refreshBall();
        try { manager.addView(ball, ballParams); refreshCursor(); }
        catch (RuntimeException problem) { ball = null; toast("悬浮球无法显示：" + problem.getMessage()); stopSelf(); }
    }
    private void positionBall() {
        Point size = screen(); ballParams.x = Math.max(0, Math.min(size.x - dp(52), Math.round(ballFractionX * size.x) - dp(26)));
        ballParams.y = Math.max(0, Math.min(size.y - dp(52), Math.round(ballFractionY * size.y) - dp(26)));
    }
    private void refreshBall() {
        if (ball == null) return;
        boolean running = bridge.running();
        if (ballAppearanceReady && ballRenderedRunning == running) return;
        ballAppearanceReady = true; ballRenderedRunning = running;
        ball.setText(running ? "运行" : "映射"); ball.setTextColor(running ? Color.rgb(7, 9, 11) : ACCENT);
        ball.setBackground(background(running ? ACCENT : Color.argb(238, 22, 28, 24), ACCENT, 26));
    }
    private void removeBall() { if (ball != null) { remove(ball); ball = null; } ballAppearanceReady = false; }
    private void showMenu() {
        dismissDialog();
        Context themed = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout menu = new LinearLayout(themed); menu.setOrientation(LinearLayout.VERTICAL); menu.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView title = new TextView(themed); title.setText(ProfileStore.current(this).optString("name", "当前配置")); title.setTextColor(Color.WHITE); title.setTextSize(18); menu.addView(title);
        TextView status = new TextView(themed); status.setText(bridge.status() + "\nF8 紧急暂停 · 所有配置保存在本机"); status.setTextColor(Color.rgb(170, 181, 171)); status.setTextSize(12); status.setPadding(0, dp(8), 0, dp(10)); menu.addView(status);
        menuButton(menu, bridge.running() ? "暂停映射" : "开始映射", () -> { if (bridge.running()) bridge.pauseMapping(); else startMapping(); });
        menuButton(menu, "编辑屏幕点位", this::openEditor);
        menuButton(menu, "回到主界面", () -> startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        menuButton(menu, "退出悬浮球和映射", () -> { bridge.stop(); stopSelf(); });
        dialog = new AlertDialog.Builder(themed).setView(menu).create(); dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnKeyListener((d, keyCode, event) -> { if (keyCode == KeyEvent.KEYCODE_F8) { if (event.getAction() == KeyEvent.ACTION_DOWN) bridge.pauseMapping(); return true; } return false; });
        dialog.show(); dialog.getWindow().setBackgroundDrawable(background(Color.rgb(31, 32, 34), 0, 20));
        dialog.getWindow().setLayout(Math.min(dp(340), screen().x - dp(24)), -2);
        dialog.setOnDismissListener(d -> refreshCursor()); refreshCursor();
    }
    private void menuButton(LinearLayout parent, String title, Runnable action) {
        Button button = button(title); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(6); parent.addView(button, lp);
        button.setOnClickListener(v -> { dismissDialog(); action.run(); });
    }
    private Button button(String title) {
        Button button = new Button(this); button.setText(title); button.setTextSize(13); button.setTextColor(ACCENT); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(14), 0, dp(14), 0); button.setBackground(background(Color.rgb(37, 43, 38), Color.rgb(54, 75, 55), 12)); return button;
    }
    private void openEditor() {
        if (editor != null) return;
        dismissDialog(); bridge.pauseMapping(); removeBall(); hideCursor();
        try { working = new JSONObject(ProfileStore.current(this).toString()); }
        catch (JSONException problem) { toast("无法读取配置"); showBall(); return; }
        editor = new FrameLayout(this); editor.setFocusableInTouchMode(true);
        canvas = new MappingCanvas(this, working); editor.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(10), dp(8), dp(10), dp(8)); panel.setBackground(background(Color.argb(238, 22, 25, 23), Color.argb(170, 68, 90, 70), 16));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); panel.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));
        selectionText = new TextView(this); selectionText.setTextColor(Color.WHITE); selectionText.setTextSize(12); selectionText.setSingleLine(true); selectionText.setEllipsize(android.text.TextUtils.TruncateAt.END); selectionText.setPadding(dp(6), 0, dp(6), 0);
        header.addView(selectionText, new LinearLayout.LayoutParams(0, -2, 1));
        tool(header, "取消", this::cancelEditor);
        Button save = tool(header, "完成保存", this::saveEditor); save.setTextColor(Color.rgb(7, 9, 11)); save.setBackground(background(ACCENT, 0, 11));
        HorizontalScrollView scroller = new HorizontalScrollView(this); scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this); scroller.addView(tools); panel.addView(scroller, new LinearLayout.LayoutParams(-1, dp(42)));
        tool(tools, "＋按键", () -> canvas.addBinding());
        tool(tools, "方向摇杆", () -> editArea(MappingCanvas.JOYSTICK));
        tool(tools, "鼠标视角", () -> editArea(MappingCanvas.AIM));
        tool(tools, "设置", () -> editSelection(canvas.selected()));
        tool(tools, "删除", () -> { if (canvas.selected() == MappingCanvas.NONE) toast("先选择点位"); else canvas.removeSelected(); });
        Button preview = tool(tools, "预览", () -> { canvas.setPreview(!canvas.isPreview()); updateSelection(); });
        preview.setOnClickListener(v -> { canvas.setPreview(!canvas.isPreview()); preview.setText(canvas.isPreview() ? "退出预览" : "预览"); updateSelection(); });

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); panelParams.leftMargin = dp(12); panelParams.rightMargin = dp(12); panelParams.topMargin = dp(36);
        editor.addView(panel, panelParams);
        editor.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
            panelParams.topMargin = Math.max(dp(16), top + dp(8));
            panelParams.leftMargin = Math.max(dp(12), insets.getSystemWindowInsetLeft() + dp(8)); panelParams.rightMargin = Math.max(dp(12), insets.getSystemWindowInsetRight() + dp(8));
            panel.setLayoutParams(panelParams); return insets;
        });
        editor.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_F8) { if (event.getAction() == KeyEvent.ACTION_DOWN) { bridge.pauseMapping(); toast("已紧急暂停映射"); } return true; }
            if (keyCode == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_UP) cancelEditor(); return true; }
            return false;
        });
        canvas.setListener(new MappingCanvas.Listener() {
            public void onSelectionChanged(int selection) { updateSelection(); }
            public void onEditRequested(int selection) { editSelection(selection); }
        });
        Point fullScreen = screen();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(fullScreen.x, fullScreen.y, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        try { manager.addView(editor, params); editor.requestFocus(); updateSelection(); }
        catch (RuntimeException problem) { editor = null; canvas = null; working = null; toast("无法打开编辑器：" + problem.getMessage()); showBall(); }
    }
    private Button tool(LinearLayout tools, String title, Runnable action) {
        Button button = button(title); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1); lp.rightMargin = dp(6); tools.addView(button, lp); button.setOnClickListener(v -> action.run()); return button;
    }
    private void updateSelection() {
        if (selectionText == null || canvas == null) return;
        int selected = canvas.selected();
        String name = selected == MappingCanvas.JOYSTICK ? "方向摇杆" : selected == MappingCanvas.AIM ? "鼠标视角" : "未选择点位";
        JSONObject item = canvas.selectedObject();
        if (selected >= 0 && item != null) name = item.optString("label", "按键") + " · " + MappingCanvas.typeLabel(item.optString("type"));
        selectionText.setText(working.optString("name", "配置") + "  ·  " + (canvas.isPreview() ? "布局预览" : name) + " · 拖动 / 点击设置");
    }
    private void editArea(int id) {
        if (working == null) return;
        String name = id == MappingCanvas.JOYSTICK ? "joystick" : "aim";
        if (working.optJSONObject(name) == null) try {
            JSONObject defaults = ProfileStore.template("默认", ""); working.put(name, defaults.getJSONObject(name));
        } catch (JSONException ignored) { }
        canvas.select(id); editSelection(id);
    }
    private void editSelection(int selection) {
        if (canvas == null || selection == MappingCanvas.NONE) { toast("先点击一个点位"); return; }
        JSONObject item = canvas.selectedObject(); if (item == null) return;
        dismissDialog();
        Runnable changed = () -> { if (canvas != null) { canvas.invalidate(); updateSelection(); } };
        if (selection == MappingCanvas.JOYSTICK) dialog = EditorDialogs.joystick(this, item, changed);
        else if (selection == MappingCanvas.AIM) dialog = EditorDialogs.aim(this, item, changed);
        else dialog = EditorDialogs.binding(this, item, changed);
        dialog.setOnDismissListener(d -> refreshCursor()); refreshCursor();
    }
    private void saveEditor() {
        if (working == null) return;
        try {
            ProfileStore.save(this, working);
            if (bridge.connected()) { Point size = screen(); bridge.configure(working, size.x, size.y); }
            closeEditor(); showBall(); toast("配置已保存，点击悬浮球开始映射");
        } catch (RuntimeException problem) { toast("保存失败：" + problem.getMessage()); }
    }
    private void cancelEditor() { closeEditor(); showBall(); toast("已取消，保留原配置"); }
    private void closeEditor() {
        dismissDialog(); if (editor != null) remove(editor); editor = null; canvas = null; selectionText = null; working = null;
    }
    private void startMapping() {
        // Never leave the large, focusable editor window above the game during mapping.
        closeEditor(); showBall();
        if (!bridge.connected()) { toast("请先回主界面激活 Root 服务"); return; }
        Point size = screen(); bridge.configure(ProfileStore.current(this), size.x, size.y); bridge.startMapping();
    }
    private void refreshCursor() {
        boolean visible = !destroyed && bridge.running() && !bridge.aimLocked() && bridge.cursorVisible()
            && editor == null && (dialog == null || !dialog.isShowing());
        if (!visible) { hideCursor(); return; }
        if (cursor == null) {
            cursor = new CursorView(this);
            cursorParams = new WindowManager.LayoutParams(dp(22), dp(26), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
            if (Build.VERSION.SDK_INT >= 30) cursorParams.setFitInsetsTypes(0);
            // Android's untrusted-overlay touch filtering allows combined opacity below 0.8.
            cursorParams.alpha = .65f;
            cursorParams.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            cursorParams.x = Math.round(bridge.cursorX()) - dp(2); cursorParams.y = Math.round(bridge.cursorY()) - dp(2);
            try { manager.addView(cursor, cursorParams); }
            catch (RuntimeException problem) { cursor = null; return; }
        } else {
            int x = Math.round(bridge.cursorX()) - dp(2), y = Math.round(bridge.cursorY()) - dp(2);
            if (cursorParams.x != x || cursorParams.y != y) {
                cursorParams.x = x; cursorParams.y = y;
                try { manager.updateViewLayout(cursor, cursorParams); } catch (IllegalArgumentException ignored) { }
            }
        }
    }
    private void hideCursor() { if (cursor != null) { remove(cursor); cursor = null; } }
    private final class CursorView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path pointer = new Path();
        CursorView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            pointer.reset(); pointer.moveTo(dp(2), dp(2)); pointer.lineTo(dp(3), dp(22)); pointer.lineTo(dp(8), dp(16));
            pointer.lineTo(dp(13), dp(25)); pointer.lineTo(dp(17), dp(23)); pointer.lineTo(dp(12), dp(14)); pointer.lineTo(dp(20), dp(13)); pointer.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(ACCENT); canvas.drawPath(pointer, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(7, 9, 11)); canvas.drawPath(pointer, paint);
        }
    }
    private void dismissDialog() { if (dialog != null) { if (dialog.isShowing()) dialog.dismiss(); dialog = null; } }
    private void remove(View view) { try { manager.removeView(view); } catch (IllegalArgumentException ignored) { } }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); dismissDialog();
        if (ball != null) { positionBall(); manager.updateViewLayout(ball, ballParams); }
        if (editor != null) {
            Point size = screen(); WindowManager.LayoutParams params = (WindowManager.LayoutParams) editor.getLayoutParams(); params.width = size.x; params.height = size.y;
            manager.updateViewLayout(editor, params);
        }
        if (canvas != null) { canvas.invalidate(); updateSelection(); }
        refreshCursor();
        if (bridge.running()) { Point size = screen(); bridge.configure(ProfileStore.current(this), size.x, size.y); }
    }
    @Override public void onDestroy() {
        destroyed = true; dismissDialog(); closeEditor(); removeBall(); hideCursor();
        bridge.removeListener(this); bridge.pauseMapping(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
