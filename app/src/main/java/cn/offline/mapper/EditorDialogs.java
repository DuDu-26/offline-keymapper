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
    private static final int TEXT = Ui.TEXT, MUTED = Ui.MUTED;
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
        CheckBox pixelRadius = form.check("用像素精确设置半径", entry.has("radiusPixels"));
        EditText pixels = form.number("精确半径（像素，1～900）", entry.optInt("radiusPixels",90));
        Spinner trajectory = form.spinner("摇杆手感", new String[]{"拟真（平滑 + 轻微漂移）", "平滑（弹簧跟随）", "直接（瞬间到位）"});
        trajectory.setSelection(entry.optInt("trajectoryMode",0));
        cn.offline.mapper.engine.JoystickTrajectory.Params defaults=cn.offline.mapper.engine.JoystickTrajectory.Params.defaults(entry.optInt("trajectoryMode",0));
        EditText settle = form.number("到位时间（毫秒，10～500）", entry.optInt("settleMs",defaults.settleMs));
        EditText wobble = form.number("按住漂移幅度（像素，0～40，仅拟真）", entry.optInt("wobblePx",defaults.wobblePx));
        EditText overshoot = form.number("推出半径之外（%，0～100）", entry.optInt("overshootPercent",defaults.overshootPercent));
        EditText startOffset = form.number("按下起点随机偏移（像素，0～60）", entry.optInt("startOffsetPx",20));
        CheckBox customAngles = form.check("自定义终点偏角范围",entry.optBoolean("customAngles",false));
        EditText angleMin = form.number("偏角下限（整数度，-45～45）",entry.optInt("angleMin",-8));
        EditText angleMax = form.number("偏角上限（整数度，-45～45）",entry.optInt("angleMax",8));
        form.note("拟真模式下每次换方向，终点会在偏角范围内随机；平滑模式使用范围中值；直接模式忽略。");
        Spinner radiusSwitch = form.spinner("半径切换",new String[]{"不切换","长按切换","按下切换"});
        radiusSwitch.setSelection(entry.optInt("radiusSwitchMode",0));
        EditText radiusKey = form.key("半径切换键",entry.optInt("radiusSwitchCode",56));
        EditText percent = form.number("切换半径（原半径 %，30～300）",entry.optInt("radiusSwitchPercent",100));
        EditText up = form.key("向上", entry.optInt("up", 17)), down = form.key("向下", entry.optInt("down", 31));
        EditText left = form.key("向左", entry.optInt("left", 30)), right = form.key("向右", entry.optInt("right", 32));
        android.widget.Button details = new android.widget.Button(form.context);
        details.setText("查看手感说明");form.fields.addView(details);
        TextView explanation = new TextView(form.context);explanation.setTextColor(MUTED);explanation.setTextSize(13);
        explanation.setText("手指被一根“弹簧”拉向摇杆边缘的目标点，按固定帧率平滑移动。换方向只更换目标，速度会延续，所以转向时自然划出弧线。\n\n"
                +"到位时间：越小越跟手，越大越柔和。\n按住漂移：拟真模式下按住方向时，手指在目标附近缓慢连续地小幅移动。\n推出半径之外：让终点略超出摇杆半径，确保游戏识别为推满。\n起点偏移：每次按下时手指落点在中心附近随机。\n\n"
                +"相反方向键互相抵消；松开最后一个方向键时在当前位置抬起。");
        explanation.setPadding(0,form.dp(8),0,form.dp(12));explanation.setVisibility(View.GONE);form.fields.addView(explanation);
        details.setOnClickListener(v->{boolean show=explanation.getVisibility()!=View.VISIBLE;explanation.setVisibility(show?View.VISIBLE:View.GONE);details.setText(show?"收起说明":"查看手感说明");});
        return form.show("方向摇杆设置", () -> {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("enabled", enabled.isChecked()).put("x", number(x, 0, 100) / 100).put("y", number(y, 0, 100) / 100)
                .put("radius", number(radius, 1, 45) / 100).put("up", key(up)).put("down", key(down)).put("left", key(left)).put("right", key(right));
            if(pixelRadius.isChecked())copy.put("radiusPixels",integer(pixels,1,900));else copy.remove("radiusPixels");
            copy.remove("trajectoryVariant");
            copy.put("trajectoryMode",trajectory.getSelectedItemPosition()).put("settleMs",integer(settle,10,500)).put("wobblePx",integer(wobble,0,40))
                .put("overshootPercent",integer(overshoot,0,100)).put("startOffsetPx",integer(startOffset,0,60))
                .put("customAngles",customAngles.isChecked()).put("angleMin",integer(angleMin,-45,45)).put("angleMax",integer(angleMax,-45,45))
                .put("radiusSwitchMode",radiusSwitch.getSelectedItemPosition()).put("radiusSwitchCode",key(radiusKey)).put("radiusSwitchPercent",integer(percent,30,300));
            cn.offline.mapper.engine.JoystickController.validateOptions(copy);
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
        EditText sx = form.slider("水平灵敏度（0.05～30）", entry.optDouble("sensitivityX", 1), .05, 5, .05);
        EditText sy = form.slider("垂直灵敏度（0.05～30）", entry.optDouble("sensitivityY", 1), .05, 5, .05);
        form.note("滑块调到 5，更大的数值可以直接在右边输入。");
        Spinner rate = form.spinner("鼠标回报率", rateNames());
        rate.setSelection(rateIndex(entry.optInt("rateHz", 0)));
        int mouseHz = RootBridge.get(context).detail().optInt("mouseHz", 0);
        form.note((mouseHz > 0 ? "检测到你的鼠标回报率约 " + mouseHz + " Hz。" : "开始映射并移动鼠标后，这里会显示检测到的鼠标回报率。")
                + "跟随鼠标：鼠标每上报一次就滑动一次。选 125 / 250 / 500 / 1000 Hz：视角按这个频率滑动，间隔内的鼠标移动会合并，总移动量不变；一般选成和鼠标驱动里设置的回报率一样，游戏里转视角卡顿时可以选低一档。");
        EditText scope = form.key("开镜键（留空或填 0 表示不用）", entry.optInt("scopeCode", 0));
        if (entry.optInt("scopeCode", 0) == 0) scope.setText("");
        Spinner scopeMode = form.spinner("开镜方式", new String[]{"按住开镜键时生效", "按一下开，再按一下关"});
        scopeMode.setSelection(entry.optInt("scopeMode", 0));
        EditText scopeScale = form.slider("开镜灵敏度倍率（0.05～5）", entry.optDouble("scopeScale", .6), .05, 2, .05);
        form.note("开镜时的灵敏度 = 上面的灵敏度 × 倍率。开镜键可以和游戏里开镜按钮绑定的键相同，例如鼠标右键。");
        EditText toggle = form.key("视角捕获切换键", entry.optInt("toggleCode", 59));
        EditText idle = form.number("鼠标停下后释放（毫秒，0～2000）", entry.optInt("idleMs", 80));
        CheckBox invert = form.check("反转垂直方向", entry.optBoolean("invertY", false));
        CheckBox lockOnStart = form.check("开始映射时直接用鼠标控制视角", entry.optBoolean("lockOnStart", true));
        form.note("关闭后，开始映射时鼠标是普通指针，需要按视角切换键（默认 F1）才会控制视角。");
        return form.show("鼠标视角设置", () -> {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("enabled", enabled.isChecked()).put("x", number(x, 0, 100) / 100).put("y", number(y, 0, 100) / 100)
                .put("radius", number(radius, 2, 45) / 100).put("sensitivityX", number(sx, .05, 30)).put("sensitivityY", number(sy, .05, 30))
                .put("toggleCode", key(toggle)).put("idleMs", Math.round(number(idle, 0, 2000))).put("invertY", invert.isChecked()).put("lockOnStart", lockOnStart.isChecked())
                .put("rateHz", cn.offline.mapper.engine.AimOptions.RATES[rate.getSelectedItemPosition()])
                .put("scopeCode", optionalKey(scope)).put("scopeMode", scopeMode.getSelectedItemPosition()).put("scopeScale", number(scopeScale, .05, 5));
            cn.offline.mapper.engine.AimOptions.validate(copy);
            merge(entry, copy); changed.run();
        });
    }
    /** Quick in-game adjustment of the view sensitivity only; {@code changed} runs after the profile entry is updated. */
    public static AlertDialog sensitivity(Context context, JSONObject entry, Runnable changed) {
        Form form = new Form(context);
        EditText sx = form.slider("水平灵敏度（0.05～30）", entry.optDouble("sensitivityX", 1), .05, 5, .05);
        EditText sy = form.slider("垂直灵敏度（0.05～30）", entry.optDouble("sensitivityY", 1), .05, 5, .05);
        EditText scopeScale = form.slider("开镜灵敏度倍率（0.05～5）", entry.optDouble("scopeScale", .6), .05, 2, .05);
        Spinner rate = form.spinner("鼠标回报率", rateNames());
        rate.setSelection(rateIndex(entry.optInt("rateHz", 0)));
        form.note("应用后立即生效。开镜键和其他视角选项在“编辑屏幕点位”里双击视角区域设置。");
        return form.show("视角灵敏度", () -> {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("sensitivityX", number(sx, .05, 30)).put("sensitivityY", number(sy, .05, 30)).put("scopeScale", number(scopeScale, .05, 5))
                .put("rateHz", cn.offline.mapper.engine.AimOptions.RATES[rate.getSelectedItemPosition()]);
            merge(entry, copy); changed.run();
        });
    }
    private static String[] rateNames() {
        int[] rates = cn.offline.mapper.engine.AimOptions.RATES; String[] names = new String[rates.length];
        for (int i = 0; i < rates.length; i++) names[i] = rates[i] == 0 ? "跟随鼠标（每次上报都发送）" : rates[i] + " Hz";
        return names;
    }
    /** Hand-edited profiles may hold a rate that is not in the list; show the nearest one. */
    private static int rateIndex(int hz) {
        int[] rates = cn.offline.mapper.engine.AimOptions.RATES; int best = 0;
        for (int i = 1; i < rates.length; i++) if (hz > 0 && (best == 0 || Math.abs(rates[i] - hz) < Math.abs(rates[best] - hz))) best = i;
        return best;
    }
    private static int optionalKey(EditText field) {
        String text = field.getText().toString().trim();
        return text.isEmpty() || text.equals("0") ? 0 : key(field);
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
    private static int integer(EditText field,int min,int max) {
        double value=number(field,min,max);
        if(value!=Math.rint(value)){field.setError("请输入整数");throw new IllegalArgumentException("请输入整数");}
        return (int)value;
    }
    private static void merge(JSONObject target, JSONObject source) throws Exception {
        java.util.ArrayList<String> removed=new java.util.ArrayList<>();
        Iterator<String> previous=target.keys();while(previous.hasNext()){String key=previous.next();if(!source.has(key))removed.add(key);}
        for(String key:removed)target.remove(key);
        Iterator<String> keys = source.keys(); while (keys.hasNext()) { String key = keys.next(); target.put(key, source.get(key)); }
    }
    private static final class Form {
        final Context context;
        final LinearLayout fields;
        Form(Context context) {
            this.context = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog);
            fields = new LinearLayout(this.context); fields.setOrientation(LinearLayout.VERTICAL);
        }
        int dp(float n) { return Ui.dp(context, n); }
        void note(String value) {
            TextView text = Ui.text(context, value, 13, Ui.MUTED);
            text.setBackground(Ui.shape(context, Ui.SURFACE_2, 12, 0)); text.setPadding(dp(12), dp(10), dp(12), dp(10));
            fields.addView(text, Ui.margins(context, Ui.fill(-2), 0, 4, 0, 6));
        }
        void title(String name) {
            TextView label = Ui.text(context, name, 13, Ui.MUTED);
            fields.addView(label, Ui.margins(context, Ui.fill(-2), 2, 12, 0, 6));
        }
        private EditText input() {
            EditText input = new EditText(context); input.setTextColor(TEXT); input.setSingleLine(true); input.setTextSize(15);
            input.setHintTextColor(Ui.FAINT);
            input.setBackground(Ui.shape(context, Ui.SURFACE_2, 12, Ui.STROKE)); input.setPadding(dp(14), 0, dp(14), 0);
            return input;
        }
        EditText number(String name, double value) {
            title(name); EditText input = input();
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", ""));
            fields.addView(input, new LinearLayout.LayoutParams(-1, dp(48))); return input;
        }
        /** Slider for the usual range with a number field beside it for exact or larger values. */
        EditText slider(String name, double value, double min, double max, double step) {
            title(name);
            LinearLayout line = Ui.row(context);
            android.widget.SeekBar bar = new android.widget.SeekBar(context);
            int steps = (int) Math.round((max - min) / step);
            bar.setMax(steps);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
            EditText input = input();
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setGravity(android.view.Gravity.CENTER);
            input.setPadding(dp(4), 0, dp(4), 0);
            input.setText(String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ""));
            bar.setProgress((int) Math.round((Math.max(min, Math.min(max, value)) - min) / step));
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar seek, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    input.setError(null);
                    input.setText(String.format(Locale.US, "%.2f", min + progress * step).replaceAll("0+$", "").replaceAll("\\.$", ""));
                }
                public void onStartTrackingTouch(android.widget.SeekBar seek) { }
                public void onStopTrackingTouch(android.widget.SeekBar seek) { }
            });
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                public void onTextChanged(CharSequence text, int start, int before, int count) { }
                public void afterTextChanged(android.text.Editable text) {
                    try {
                        double typed = Double.parseDouble(text.toString().trim());
                        bar.setProgress((int) Math.round((Math.max(min, Math.min(max, typed)) - min) / step));
                    } catch (NumberFormatException incomplete) { /* Keep the slider where it is while the user is typing. */ }
                }
            });
            line.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
            line.addView(input, Ui.margins(context, new LinearLayout.LayoutParams(dp(76), dp(48)), 8, 0, 0, 0));
            fields.addView(line, Ui.fill(-2)); return input;
        }
        EditText key(String name, int code) {
            title(name);
            LinearLayout line = Ui.row(context);
            EditText input = input(); input.setInputType(InputType.TYPE_CLASS_TEXT);
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
            line.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
            Spinner picker = new Spinner(context); String[] labels = BindingKey.labels();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels); picker.setAdapter(adapter);
            picker.setBackground(Ui.shape(context, Ui.SURFACE_3, 12, 0));
            int position = 0; for (int i = 0; i < labels.length; i++) if (BindingKey.codeFromLabel(labels[i]) == code) position = i;
            picker.setSelection(position); picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                boolean ready;
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                    if (ready) input.setText(labels[pos]); ready = true;
                }
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            picker.setContentDescription("从列表选择按键");
            line.addView(picker, Ui.margins(context, new LinearLayout.LayoutParams(dp(112), dp(48)), 8, 0, 0, 0));
            fields.addView(line, Ui.fill(-2)); return input;
        }
        Spinner spinner(String name, String[] options) {
            title(name); Spinner spinner = new Spinner(context);
            spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, options));
            spinner.setBackground(Ui.shape(context, Ui.SURFACE_2, 12, Ui.STROKE));
            fields.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48))); return spinner;
        }
        CheckBox check(String name, boolean checked) {
            CheckBox box = new CheckBox(context); box.setText(name); box.setTextColor(TEXT); box.setTextSize(15); box.setChecked(checked);
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            fields.addView(box, Ui.margins(context, Ui.fill(dp(48)), 0, 6, 0, 0)); return box;
        }
        AlertDialog show(String title, Apply apply) {
            Sheet sheet = new Sheet(context, title, null, true);
            sheet.body.addView(fields, Ui.fill(-2));
            sheet.action("取消", Ui.Kind.GHOST, null).action("应用", Ui.Kind.PRIMARY, apply::apply);
            AlertDialog dialog = sheet.dialog;
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_F8) { if (event.getAction() == KeyEvent.ACTION_DOWN) RootBridge.get(context).pauseMapping(); return true; } return false;
            });
            sheet.show();
            Window w = dialog.getWindow();
            if (w != null) {
                android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                int dialogWidth = Math.min(dp(440), metrics.widthPixels - dp(24));
                w.setLayout(dialogWidth, Math.min(dp(650), metrics.heightPixels - dp(40)));
                // Overlay dialogs keep their requested height when the IME opens; cap it
                // to the visible display frame so the action buttons stay reachable.
                w.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    android.graphics.Rect available = new android.graphics.Rect();
                    w.getDecorView().getWindowVisibleDisplayFrame(available);
                    int height = Math.min(dp(650), Math.max(dp(180), available.height() - dp(32)));
                    if (available.height() > 0 && w.getAttributes().height != height) w.setLayout(dialogWidth, height);
                });
            }
            return dialog;
        }
    }
    private EditorDialogs() { }
}
