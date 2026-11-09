package cn.offline.mapper;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local test surface. Shows every touch with a fading trail so the joystick path
 * can be inspected, and writes a small event log for automated tests.
 * Never forwards touches to another application.
 */
public class TouchLabActivity extends Activity {
    private static final long TRAIL_MS = 1600;
    private static final int[] COLORS = {Ui.ACCENT, Ui.SUCCESS, Ui.WARN, 0xffb07cff, 0xff4fd1e8, Ui.DANGER, 0xffff9f43, 0xffa3e635, 0xfff472b6, 0xffe5e7eb};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(new Lab());
    }

    private static final class Sample { final float x, y; final long time; Sample(float x, float y, long time) { this.x = x; this.y = y; this.time = time; } }
    private static final class Trail { final ArrayList<Sample> points = new ArrayList<>(); final int color; boolean down = true; long upAt; Trail(int color) { this.color = color; } }

    private final class Lab extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final JSONArray history = new JSONArray();
        private final Map<Integer, Trail> live = new HashMap<>();
        private final ArrayList<Trail> fading = new ArrayList<>();
        private String last = "等待输入";
        private int maxPointers, count;

        Lab() {
            super(TouchLabActivity.this);
            setBackgroundColor(Ui.BG);
            setFocusableInTouchMode(true);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            try (FileOutputStream out = openFileOutput("touch-lab.json", MODE_PRIVATE)) {
                out.write("{\"events\":0,\"maxPointers\":0,\"active\":0,\"history\":[]}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }

        private float dp(float n) { return n * getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            long now = SystemClock.uptimeMillis();
            grid(c);
            targets(c);
            for (Iterator<Trail> it = fading.iterator(); it.hasNext(); ) {
                Trail t = it.next();
                if (now - t.upAt > TRAIL_MS) it.remove(); else trail(c, t, now);
            }
            for (Trail t : live.values()) trail(c, t, now);
            for (Map.Entry<Integer, Trail> e : live.entrySet()) finger(c, e.getKey(), e.getValue());
            panel(c);
            if (!fading.isEmpty() || !live.isEmpty()) postInvalidateOnAnimation();
        }

        private void grid(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff151a24);
            float step = dp(48);
            for (float x = step; x < getWidth(); x += step) c.drawLine(x, 0, x, getHeight(), paint);
            for (float y = step; y < getHeight(); y += step) c.drawLine(0, y, getWidth(), y, paint);
        }

        private void targets(Canvas c) {
            float jx = getWidth() * .18f, jy = getHeight() * .75f, jr = Math.min(getWidth(), getHeight()) * .075f;
            ring(c, jx, jy, jr, Ui.SUCCESS, "WASD");
            ring(c, getWidth() * .87f, getHeight() * .48f, dp(28), Ui.ACCENT, "左键");
            ring(c, getWidth() * .85f, getHeight() * .78f, dp(28), Ui.ACCENT, "空格");
        }

        private void ring(Canvas c, float x, float y, float r, int color, String label) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(Ui.alpha(color, 22)); c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f)); paint.setColor(Ui.alpha(color, 200)); c.drawCircle(x, y, r, paint);
            text(c, label, x, y + dp(4), 12, Ui.alpha(color, 230), Paint.Align.CENTER, true);
        }

        private void trail(Canvas c, Trail t, long now) {
            ArrayList<Sample> s = t.points;
            if (s.size() < 2) return;
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 1; i < s.size(); i++) {
                Sample a = s.get(i - 1), b = s.get(i);
                long age = now - b.time;
                float life = t.down ? Math.max(.25f, 1 - age / (float) (TRAIL_MS * 2)) : Math.max(0, 1 - (now - t.upAt) / (float) TRAIL_MS);
                paint.setColor(Ui.alpha(t.color, Math.round(220 * life)));
                paint.setStrokeWidth(dp(3));
                c.drawLine(a.x, a.y, b.x, b.y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            for (Sample p : s) { paint.setColor(Ui.alpha(t.color, t.down ? 160 : 90)); c.drawCircle(p.x, p.y, dp(1.6f), paint); }
        }

        private void finger(Canvas c, int id, Trail t) {
            Sample p = t.points.get(t.points.size() - 1);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Ui.alpha(t.color, 70)); c.drawCircle(p.x, p.y, dp(26), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(t.color); c.drawCircle(p.x, p.y, dp(26), paint);
            text(c, String.valueOf(id), p.x, p.y + dp(5), 14, Ui.TEXT, Paint.Align.CENTER, true);
        }

        private void panel(Canvas c) {
            float left = dp(20), top = dp(20);
            rect.set(left, top, left + dp(300), top + dp(118));
            paint.setStyle(Paint.Style.FILL); paint.setColor(Ui.alpha(Ui.SURFACE, 235)); c.drawRoundRect(rect, dp(18), dp(18), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(Ui.STROKE); c.drawRoundRect(rect, dp(18), dp(18), paint);
            text(c, "触控练习场", left + dp(16), top + dp(30), 18, Ui.TEXT, Paint.Align.LEFT, true);
            text(c, "开始映射后按 WASD、左键、空格，查看手指轨迹", left + dp(16), top + dp(52), 12, Ui.MUTED, Paint.Align.LEFT, false);
            text(c, "事件 " + count + "   同时触点 " + live.size() + "   最多 " + maxPointers, left + dp(16), top + dp(78), 13, Ui.ACCENT, Paint.Align.LEFT, true);
            text(c, last, left + dp(16), top + dp(100), 12, Ui.FAINT, Paint.Align.LEFT, false);
        }

        private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, boolean bold) {
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(size)); paint.setColor(color); paint.setTextAlign(align);
            paint.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
            c.drawText(value, x, y, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            count++;
            maxPointers = Math.max(maxPointers, e.getPointerCount());
            last = MotionEvent.actionToString(e.getAction()) + "  " + Math.round(e.getX()) + ", " + Math.round(e.getY());
            int action = e.getActionMasked();
            // Include batched historical samples so fast joystick moves draw a faithful path.
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                Trail t = live.get(id);
                if (t == null) { t = new Trail(COLORS[Math.abs(id) % COLORS.length]); live.put(id, t); }
                for (int h = 0; h < e.getHistorySize(); h++) t.points.add(new Sample(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h)));
                t.points.add(new Sample(e.getX(i), e.getY(i), e.getEventTime()));
                if (t.points.size() > 600) t.points.subList(0, t.points.size() - 600).clear();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                for (Trail t : live.values()) lift(t);
                live.clear();
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                Trail t = live.remove(e.getPointerId(e.getActionIndex()));
                if (t != null) lift(t);
            }
            log(e);
            invalidate();
            return true;
        }

        private void lift(Trail t) { t.down = false; t.upAt = SystemClock.uptimeMillis(); fading.add(t); }

        private void log(MotionEvent e) {
            try {
                JSONObject row = new JSONObject().put("action", e.getActionMasked()).put("index", e.getActionIndex()).put("count", e.getPointerCount())
                        .put("eventTime", e.getEventTime()).put("downTime", e.getDownTime());
                JSONArray p = new JSONArray();
                for (int i = 0; i < e.getPointerCount(); i++) p.put(new JSONObject().put("id", e.getPointerId(i)).put("x", e.getX(i)).put("y", e.getY(i)));
                row.put("pointers", p);
                history.put(row);
                if (history.length() > 300) history.remove(0);
                JSONObject report = new JSONObject().put("events", count).put("maxPointers", maxPointers).put("active", live.size()).put("history", history);
                try (FileOutputStream out = openFileOutput("touch-lab.json", MODE_PRIVATE)) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) {}
        }
    }
}
