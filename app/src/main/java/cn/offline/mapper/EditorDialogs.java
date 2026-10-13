package cn.offline.mapper;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

/** Settings dialogs use an overlay window so they remain available over the game. */
public final class EditorDialogs {
    private static final int TEXT = Color.rgb(233, 240, 234), MUTED = Color.rgb(155, 172, 158);
    private interface Apply { void apply() throws Exception; }
    public static AlertDialog binding(Context context, JSONObject entry, Runnable changed) {
        Form form = new Form(context);
        form.note("拖动圆点定位；这里设置输入键和触摸动作。F8 为紧急暂停键，不能分配。");
        EditText key = form.key("触发按键", entry.optInt("code", 18));
        String[] names = {"按住：按下触摸，松开释放", "单击：每次按下点击一次", "切换按住：再次按键释放", "连点：按住时持续点击", "滑动：按下执行一次滑动"};
        String[] types = {"hold", "tap", "toggle", "repeat", "swipe"};
        Spinner type = form.spinner("触摸类型", names);
        for (int i = 0; i < types.length; i++) if (types[i].equals(entry.optString("type"))) type.setSelection(i);
        EditText x = form.number("起点 X（屏幕宽度 %）", entry.optDouble("x", .5) * 100);
        EditText y = form.number("起点 Y（屏幕高度 %）", entry.optDouble("y", .5) * 100);
        EditText endX = form.number("滑动终点 X（%）", entry.optDouble("endX", .65) * 100);
        EditText endY = form.number("滑动终点 Y（%）", entry.optDouble("endY", .5) * 100);
        EditText duration = form.number("单击 / 滑动时长（毫秒，16～10000）", entry.optInt("duration", 160));
        EditText interval = form.number("连点间隔（毫秒，30～10000）", entry.optInt("interval", 100));
        return form.show("按键点位设置", () -> {
            int code = key(key);
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("code", code).put("label", BindingKey.label(code)).put("type", types[type.getSelectedItemPosition()]);
            copy.put("x", number(x, 0, 100) / 100).put("y", number(y, 0, 100) / 100)
                .put("endX", number(endX, 0, 100) / 100).put("endY", number(endY, 0, 100) / 100)
                .put("duration", Math.round(number(duration, 16, 10000))).put("interval", Math.round(number(interval, 30, 10000)));
            merge(entry, copy); changed.run();
        });
    }
    public static AlertDialog joystick(Context context, JSONObject entry, Runnable changed) {
        Form form = new Form(context);
        form.note("将摇杆中心拖到游戏的移动区域。半径按屏幕短边计算，方向键不能与其他点位重复。");
        CheckBox enabled = form.check("启用方向摇杆", entry.optBoolean("enabled", true));
        EditText x = form.number("中心 X（屏幕宽度 %）", entry.optDouble("x", .18) * 100);
        EditText y = form.number("中心 Y（屏幕高度 %）", entry.optDouble("y", .75) * 100);
        EditText radius = form.number("半径（屏幕短边 %，1～45）", entry.optDouble("radius", .075) * 100);
        EditText up = form.key("向上", entry.optInt("up", 17)), down = form.key("向下", entry.optInt("down", 31));
        EditText left = form.key("向左", entry.optInt("left", 30)), right = form.key("向右", entry.optInt("right", 32));
        return form.show("方向摇杆设置", () -> {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("enabled", enabled.isChecked()).put("x", number(x, 0, 100) / 100).put("y", number(y, 0, 100) / 100)
                .put("radius", number(radius, 1, 45) / 100).put("up", key(up)).put("down", key(down)).put("left", key(left)).put("right", key(right));
            merge(entry, copy); changed.run();
        });
    }
    public static AlertDialog aim(Context context, JSONObject entry, Runnable changed) {
        Form form = new Form(context);
        form.note("鼠标移动转换成视角区域内的滑动；切换键开启 / 关闭视角捕获。按 F8 可紧急暂停全部映射。");
        CheckBox enabled = form.check("启用鼠标视角", entry.optBoolean("enabled", true));
        EditText x = form.number("中心 X（屏幕宽度 %）", entry.optDouble("x", .65) * 100);
        EditText y = form.number("中心 Y（屏幕高度 %）", entry.optDouble("y", .5) * 100);
        EditText radius = form.number("滑动范围半径（屏幕短边 %，2～45）", entry.optDouble("radius", .2) * 100);
        EditText sx = form.number("水平灵敏度（0.05～30）", entry.optDouble("sensitivityX", 1));
        EditText sy = form.number("垂直灵敏度（0.05～30）", entry.optDouble("sensitivityY", 1));
        EditText toggle = form.key("视角捕获切换键", entry.optInt("toggleCode", 59));
        EditText idle = form.number("鼠标停下后释放（毫秒，0～2000）", entry.optInt("idleMs", 80));
        CheckBox invert = form.check("反转垂直方向", entry.optBoolean("invertY", false));
        return form.show("鼠标视角设置", () -> {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("enabled", enabled.isChecked()).put("x", number(x, 0, 100) / 100).put("y", number(y, 0, 100) / 100)
                .put("radius", number(radius, 2, 45) / 100).put("sensitivityX", number(sx, .05, 30)).put("sensitivityY", number(sy, .05, 30))
                .put("toggleCode", key(toggle)).put("idleMs", Math.round(number(idle, 0, 2000))).put("invertY", invert.isChecked());
            merge(entry, copy); changed.run();
        });
    }
    private static double number(EditText field, double min, double max) {
        double result;
        try { result = Double.parseDouble(field.getText().toString().trim()); }
        catch (NumberFormatException error) { field.requestFocus(); field.setError("请输入数字"); throw new IllegalArgumentException("请输入有效数字"); }
        if (!Double.isFinite(result) || result < min || result > max) {
            field.requestFocus(); field.setError("范围 " + min + "～" + max); throw new IllegalArgumentException("数值超出范围");
        }
        return result;
    }
    private static int key(EditText field) {
        int result = BindingKey.codeFromLabel(field.getText().toString());
        if (result < 1 || result > 769 || result == 66) {
            field.requestFocus(); field.setError(result == 66 ? "F8 已保留为紧急暂停键" : "请输入按键名称或 Linux 键码");
            throw new IllegalArgumentException("按键无效");
        }
        return result;
    }
    private static void merge(JSONObject target, JSONObject source) throws Exception {
        Iterator<String> keys = source.keys(); while (keys.hasNext()) { String key = keys.next(); target.put(key, source.get(key)); }
    }
    private static final class Form {
        final Context context;
        final LinearLayout fields;
        final ScrollView scroll;
        final float density;
        final TextView error;
        Form(Context context) {
            this.context = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);
            density = context.getResources().getDisplayMetrics().density;
            fields = new LinearLayout(this.context); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(20), dp(8), dp(20), dp(12));
            scroll = new ScrollView(this.context); scroll.setFillViewport(true); scroll.addView(fields);
            error = new TextView(this.context); error.setTextColor(Color.rgb(255, 145, 145)); error.setTextSize(13);
        }
        int dp(float n) { return Math.round(n * density); }
        void note(String value) { TextView text = new TextView(context); text.setText(value); text.setTextColor(MUTED); text.setTextSize(13); text.setPadding(0, dp(5), 0, dp(12)); fields.addView(text); }
        void title(String name) { TextView label = new TextView(context); label.setText(name); label.setTextColor(TEXT); label.setTextSize(13); label.setPadding(0, dp(10), 0, dp(3)); fields.addView(label); }
        EditText number(String name, double value) {
            title(name); EditText input = new EditText(context); input.setTextColor(TEXT); input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setText(String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", ""));
            fields.addView(input, new LinearLayout.LayoutParams(-1, dp(48))); return input;
        }
        EditText key(String name, int code) {
            title(name); EditText input = new EditText(context); input.setTextColor(TEXT); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(BindingKey.label(code)); input.setHint("按实体键，或输入名称 / 键码"); input.setSelectAllOnFocus(true);
            input.setOnKeyListener((view, keyCode, event) -> {
                if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;
                int linux = BindingKey.linuxFromAndroid(keyCode);
                if (linux < 0) return false;
                if (keyCode == KeyEvent.KEYCODE_F8) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) { RootBridge.get(context).pauseMapping(); input.setError("F8 已紧急暂停映射，不能分配"); }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) { input.setText(BindingKey.label(linux)); input.selectAll(); }
                return true;
            });
            fields.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
            Spinner picker = new Spinner(context); String[] labels = BindingKey.labels();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels); picker.setAdapter(adapter);
            int position = 0; for (int i = 0; i < labels.length; i++) if (BindingKey.codeFromLabel(labels[i]) == code) position = i;
            picker.setSelection(position); picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                boolean ready;
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                    if (ready) input.setText(labels[pos]); ready = true;
                }
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            fields.addView(picker, new LinearLayout.LayoutParams(-1, dp(44))); return input;
        }
        Spinner spinner(String name, String[] options) { title(name); Spinner spinner = new Spinner(context); spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, options)); fields.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48))); return spinner; }
        CheckBox check(String name, boolean checked) { CheckBox box = new CheckBox(context); box.setText(name); box.setTextColor(TEXT); box.setChecked(checked); fields.addView(box); return box; }
        AlertDialog show(String title, Apply apply) {
            fields.addView(error);
            AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(scroll).setPositiveButton("应用", null).setNegativeButton("取消", null).create();
            Window window = dialog.getWindow();
            if (window != null) { window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_F8) { if (event.getAction() == KeyEvent.ACTION_DOWN) RootBridge.get(context).pauseMapping(); return true; } return false;
            });
            dialog.setOnShowListener(d -> {
                Window w = dialog.getWindow();
                if (w != null) {
                    GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(31, 32, 34)); background.setCornerRadius(dp(20)); w.setBackgroundDrawable(background);
                    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                    int dialogWidth = Math.min(dp(430), metrics.widthPixels - dp(24));
                    w.setLayout(dialogWidth, Math.min(dp(650), metrics.heightPixels - dp(40)));
                    // Overlay dialogs keep their requested height when the IME opens; cap it
                    // to the visible display frame so the Apply/Cancel buttons stay reachable.
                    w.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                        android.graphics.Rect available = new android.graphics.Rect();
                        w.getDecorView().getWindowVisibleDisplayFrame(available);
                        int height = Math.min(dp(650), Math.max(dp(180), available.height() - dp(32)));
                        if (available.height() > 0 && w.getAttributes().height != height) w.setLayout(dialogWidth, height);
                    });
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(68, 214, 44));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try { apply.apply(); dialog.dismiss(); }
                    catch (Exception problem) { error.setText(problem.getMessage() == null ? "设置无效" : problem.getMessage()); }
                });
            });
            dialog.show(); return dialog;
        }
    }
    private EditorDialogs() { }
}
