package cn.offline.mapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Screen coordinates are stored as fractions of the complete overlay dimensions. */
public final class MappingCanvas extends View {
    public static final int NONE = -1, JOYSTICK = -2, AIM = -3;
    public interface Listener {
        void onSelectionChanged(int selection);
        void onEditRequested(int selection);
    }
    private final JSONObject profile;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density, touchSlop;
    private Listener listener;
    private int selected = NONE, touchTarget = NONE, pointer = -1;
    private float downX, downY, initialX, initialY;
    private boolean dragged, preview, touchEnd;
    private static final int ACCENT = Ui.ACCENT, JOY = Ui.SUCCESS, AIM_COLOR = 0xffb07cff;

    public MappingCanvas(Context context, JSONObject profile) {
        super(context);
        this.profile = profile;
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setContentDescription("映射编辑画布，拖动点位调整位置，点击点位设置按键");
    }
    public void setListener(Listener value) { listener = value; }
    public int selected() { return selected; }
    public void select(int value) {
        selected = value;
        invalidate();
        if (listener != null) listener.onSelectionChanged(value);
    }
    public void setPreview(boolean value) { preview = value; invalidate(); }
    public boolean isPreview() { return preview; }
    public JSONObject selectedObject() { return objectFor(selected); }
    private JSONArray bindings() { return profile.optJSONArray("bindings"); }
    private JSONObject objectFor(int selection) {
        if (selection == JOYSTICK) return profile.optJSONObject("joystick");
        if (selection == AIM) return profile.optJSONObject("aim");
        JSONArray entries = bindings();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && entry.optInt("id", 0) == selection) return entry;
        }
        return null;
    }
    public void addBinding() {
        try {
            JSONArray entries = bindings();
            if (entries == null) { entries = new JSONArray(); profile.put("bindings", entries); }
            if (entries.length() >= 20) { Toast.makeText(getContext(), "最多添加 20 个按键点位", Toast.LENGTH_SHORT).show(); return; }
            Set<Integer> ids = new HashSet<>(), used = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item != null) { ids.add(item.optInt("id")); used.add(item.optInt("code")); }
            }
            JSONObject joystick = profile.optJSONObject("joystick"), aim = profile.optJSONObject("aim");
            if (joystick != null && joystick.optBoolean("enabled")) for (String key : new String[]{"up", "down", "left", "right"}) used.add(joystick.optInt(key));
            if (joystick != null && joystick.optBoolean("enabled") && joystick.optInt("radiusSwitchMode",0)!=0) used.add(joystick.optInt("radiusSwitchCode",-1));
            if (aim != null && aim.optBoolean("enabled")) used.add(aim.optInt("toggleCode", 59));
            used.add(66);
            int next = 1; while (ids.contains(next)) next++;
            int code = 18;
            if (used.contains(code)) for (String label : BindingKey.labels()) {
                int candidate = BindingKey.codeFromLabel(label);
                if (!used.contains(candidate)) { code = candidate; break; }
            }
            JSONObject entry = new JSONObject();
            entry.put("id", next).put("type", "hold").put("code", code).put("label", BindingKey.label(code))
                .put("x", .5).put("y", .5).put("endX", .65).put("endY", .5)
                .put("duration", 160).put("interval", 100);
            entries.put(entry);
            select(next);
            if (listener != null) listener.onEditRequested(next);
        } catch (JSONException ignored) { }
    }
    public void removeSelected() {
        JSONArray entries = bindings();
        if (selected >= 0 && entries != null) {
            for (int i = entries.length() - 1; i >= 0; i--) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && entry.optInt("id", 0) == selected) entries.remove(i);
            }
        } else {
            JSONObject object = selectedObject();
            if (object != null) try { object.put("enabled", false); } catch (JSONException ignored) { }
        }
        select(NONE);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!preview) {
            canvas.drawColor(Color.argb(70, 8, 10, 16));
            paint.setStrokeWidth(density);
            paint.setColor(Color.argb(25, 255, 255, 255));
            for (int i = 1; i < 10; i++) {
                canvas.drawLine(getWidth() * i / 10f, 0, getWidth() * i / 10f, getHeight(), paint);
                canvas.drawLine(0, getHeight() * i / 10f, getWidth(), getHeight() * i / 10f, paint);
            }
        }
        drawArea(canvas, profile.optJSONObject("aim"), AIM, "鼠标视角", AIM_COLOR);
        drawArea(canvas, profile.optJSONObject("joystick"), JOYSTICK, "方向摇杆", JOY);
        JSONArray entries = bindings();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) drawBinding(canvas, entry);
        }
    }
    private void drawArea(Canvas canvas, JSONObject entry, int id, String title, int color) {
        if (entry == null || !entry.optBoolean("enabled", false)) return;
        float x = x(entry), y = y(entry), radius = radius(entry);
        paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(color, preview ? 15 : 25));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth((selected == id ? 3 : 1.5f) * density);
        paint.setColor(withAlpha(color, preview ? 130 : 230)); canvas.drawCircle(x, y, radius, paint);
        if (id == JOYSTICK) {
            canvas.drawLine(x - radius * .6f, y, x + radius * .6f, y, paint);
            canvas.drawLine(x, y - radius * .6f, x, y + radius * .6f, paint);
        } else {
            canvas.drawLine(x - 10 * density, y, x + 10 * density, y, paint);
            canvas.drawLine(x, y - 10 * density, x, y + 10 * density, paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(withAlpha(color, 230));
        canvas.drawCircle(x, y, 6 * density, paint);
        label(canvas, title, x, y - radius - 12 * density, color);
    }
    private void drawBinding(Canvas canvas, JSONObject entry) {
        int id = entry.optInt("id", 0);
        float x = x(entry), y = y(entry), radius = 23 * density;
        if ("swipe".equals(entry.optString("type"))) {
            float ex = (float) entry.optDouble("endX", .65) * getWidth();
            float ey = (float) entry.optDouble("endY", .5) * getHeight();
            paint.setStyle(Paint.Style.STROKE); paint.setColor(withAlpha(ACCENT, 200)); paint.setStrokeWidth(2 * density);
            canvas.drawLine(x, y, ex, ey, paint); canvas.drawCircle(ex, ey, 10 * density, paint);
            double angle = Math.atan2(ey - y, ex - x);
            Path arrow = new Path(); arrow.moveTo(ex, ey);
            arrow.lineTo(ex - (float) Math.cos(angle - .5) * 15 * density, ey - (float) Math.sin(angle - .5) * 15 * density);
            arrow.moveTo(ex, ey);
            arrow.lineTo(ex - (float) Math.cos(angle + .5) * 15 * density, ey - (float) Math.sin(angle + .5) * 15 * density);
            canvas.drawPath(arrow, paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(preview ? 110 : 225, 20, 24, 33));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(ACCENT); paint.setStrokeWidth((selected == id ? 3 : 1.5f) * density);
        canvas.drawCircle(x, y, radius, paint);
        if (selected == id) { paint.setColor(Color.WHITE); paint.setStrokeWidth(density); canvas.drawCircle(x, y, radius + 5 * density, paint); }
        String title = entry.optString("label", BindingKey.label(entry.optInt("code", 57)));
        if (title.length() > 6) title = title.substring(0, 5) + "…";
        label(canvas, title, x, y, Ui.TEXT);
        if (!preview) label(canvas, typeLabel(entry.optString("type")), x, y + 35 * density, Ui.MUTED);
    }
    public static String typeLabel(String type) {
        if ("tap".equals(type)) return "单击";
        if ("toggle".equals(type)) return "切换按住";
        if ("repeat".equals(type)) return "连点";
        if ("swipe".equals(type)) return "滑动";
        return "按住";
    }
    private void label(Canvas canvas, String value, float x, float y, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(12 * density); paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color); canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }
    private float x(JSONObject entry) { return (float) entry.optDouble("x", .5) * getWidth(); }
    private float y(JSONObject entry) { return (float) entry.optDouble("y", .5) * getHeight(); }
    private float radius(JSONObject entry) {
        return entry==profile.optJSONObject("joystick")&&entry.has("radiusPixels")?entry.optInt("radiusPixels"):
                (float)entry.optDouble("radius",.1)*Math.min(getWidth(),getHeight());
    }
    private static int withAlpha(int color, int alpha) { return (color & 0xffffff) | (alpha << 24); }
    private int hit(float x, float y) {
        touchEnd = false;
        JSONArray entries = bindings();
        if (entries != null) for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && Math.hypot(x - x(entry), y - y(entry)) < 34 * density) return entry.optInt("id", 0);
            if (entry != null && "swipe".equals(entry.optString("type"))
                    && Math.hypot(x - entry.optDouble("endX", .65) * getWidth(), y - entry.optDouble("endY", .5) * getHeight()) < 25 * density) {
                touchEnd = true; return entry.optInt("id", 0);
            }
        }
        JSONObject joystick = profile.optJSONObject("joystick");
        if (areaHit(joystick, x, y)) return JOYSTICK;
        JSONObject aim = profile.optJSONObject("aim");
        if (areaHit(aim, x, y)) return AIM;
        return NONE;
    }
    private boolean areaHit(JSONObject entry, float tx, float ty) {
        return entry != null && entry.optBoolean("enabled", false)
            && Math.hypot(tx - x(entry), ty - y(entry)) < Math.max(30 * density,
                radius(entry));
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pointer = event.getPointerId(0); downX = event.getX(); downY = event.getY(); dragged = false;
            touchTarget = hit(downX, downY); select(touchTarget);
            JSONObject entry = objectFor(touchTarget);
            if (entry != null) { initialX = (float) entry.optDouble(touchEnd ? "endX" : "x", .5); initialY = (float) entry.optDouble(touchEnd ? "endY" : "y", .5); }
            return true;
        }
        int index = event.findPointerIndex(pointer);
        if (index < 0) return true;
        if ((event.getActionMasked() == MotionEvent.ACTION_MOVE || (event.getActionMasked() == MotionEvent.ACTION_UP && dragged)) && touchTarget != NONE) {
            float dx = event.getX(index) - downX, dy = event.getY(index) - downY;
            if (Math.hypot(dx, dy) > touchSlop) dragged = true;
            if (dragged) {
                JSONObject entry = objectFor(touchTarget);
                if (entry != null && getWidth() > 0 && getHeight() > 0) try {
                    double nx = clamp(initialX + dx / getWidth()), ny = clamp(initialY + dy / getHeight());
                    if (!touchEnd && "swipe".equals(entry.optString("type"))) {
                        double ox = entry.optDouble("x", .5), oy = entry.optDouble("y", .5);
                        entry.put("endX", clamp(entry.optDouble("endX", .65) + nx - ox));
                        entry.put("endY", clamp(entry.optDouble("endY", .5) + ny - oy));
                    }
                    entry.put(touchEnd ? "endX" : "x", nx).put(touchEnd ? "endY" : "y", ny); invalidate();
                } catch (JSONException ignored) { }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!dragged && touchTarget != NONE && listener != null) listener.onEditRequested(touchTarget);
            pointer = -1; performClick(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { pointer = -1; return true; }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private static double clamp(double number) { return Math.max(0, Math.min(1, number)); }
}
