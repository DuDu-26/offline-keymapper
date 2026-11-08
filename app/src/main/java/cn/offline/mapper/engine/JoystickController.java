package cn.offline.mapper.engine;

import org.json.JSONObject;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WASD key state to a single joystick touch.
 *
 * <p>Opposite keys cancel, the touch goes down on the first direction and lifts at
 * its last position when every direction key is released. A radius key can enlarge
 * or shrink the stick while held or as a toggle.
 */
public final class JoystickController {
    static final int POINTER = 30;
    private final TouchSink touch;
    private final float centerX, centerY, baseRadius, switchedRadius;
    private final int width, height, switchMode, startOffsetPx;
    private final JoystickTrajectory.Params params;
    private final JoystickTrajectory.State state = new JoystickTrajectory.State();
    private final Random random = new Random();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapper-joystick"); thread.setDaemon(true); return thread;
    });
    private final AtomicReference<Throwable> pendingFailure = new AtomicReference<>();
    private Future<?> motion;
    private volatile long generation;
    private boolean switched, closed;
    private int dx, dy;

    JoystickController(TouchSink touch, JSONObject j, int width, int height) {
        validateOptions(j);
        this.touch = touch; this.width = width; this.height = height;
        centerX = Math.min(width - 1, (int) (j.optDouble("x", .18) * width));
        centerY = Math.min(height - 1, (int) (j.optDouble("y", .75) * height));
        int radius = j.has("radiusPixels") ? j.optInt("radiusPixels")
                : (int) (j.optDouble("radius", .075) * Math.min(width, height));
        baseRadius = radius;
        switchedRadius = radius * j.optInt("radiusSwitchPercent", 100) / 100f;
        switchMode = j.optInt("radiusSwitchMode", 0);
        params = params(j);
        startOffsetPx = params.style == JoystickTrajectory.DIRECT ? 0 : j.optInt("startOffsetPx", 20);
    }

    /** Reads trajectory parameters, falling back to the defaults of the chosen style. */
    static JoystickTrajectory.Params params(JSONObject j) {
        int style = j.optInt("trajectoryMode", JoystickTrajectory.NATURAL);
        JoystickTrajectory.Params d = JoystickTrajectory.Params.defaults(style);
        boolean custom = j.optBoolean("customAngles", false);
        return new JoystickTrajectory.Params(style,
                j.optInt("settleMs", d.settleMs), j.optInt("wobblePx", d.wobblePx),
                j.optInt("overshootPercent", d.overshootPercent),
                custom ? j.optInt("angleMin", d.angleMin) : d.angleMin,
                custom ? j.optInt("angleMax", d.angleMax) : d.angleMax);
    }

    public static void validateOptions(JSONObject j) {
        if (j == null) return;
        integer(j, "trajectoryMode", 0, 0, 2);
        integer(j, "settleMs", 70, 10, 500);
        integer(j, "wobblePx", 4, 0, 40);
        integer(j, "overshootPercent", 15, 0, 100);
        integer(j, "startOffsetPx", 20, 0, 60);
        integer(j, "angleMin", -8, -45, 45); integer(j, "angleMax", 8, -45, 45);
        if (j.optInt("angleMin", -8) > j.optInt("angleMax", 8)) throw new IllegalArgumentException("角度下限不能大于上限");
        integer(j, "radiusSwitchMode", 0, 0, 2);
        integer(j, "radiusSwitchPercent", 100, 30, 300);
        if (j.has("radiusPixels")) integer(j, "radiusPixels", 90, 1, 900);
        if (j.optInt("radiusSwitchMode", 0) != 0) {
            int code = j.optInt("radiusSwitchCode", -1);
            if (code < 1 || code > 769 || code == 66 || code == 114 || code == 115 || code == 116)
                throw new IllegalArgumentException("请设置有效的半径切换键");
        }
    }

    /** Converts settings written by older versions into the current schema. */
    public static void migrate(JSONObject j) {
        if (j == null) return;
        try {
            j.remove("trajectoryVariant");
            // Older versions allowed ±180°; clamp those to the current ±45° range.
            for (String name : new String[] {"angleMin", "angleMax"}) {
                double value = j.optDouble(name, 0);
                if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) > 45)
                    j.put(name, value > 0 ? 45 : -45);
            }
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void integer(JSONObject j, String name, int fallback, int min, int max) {
        double value = j.optDouble(name, fallback);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " 须为 " + min + "～" + max + " 的整数");
    }

    synchronized void direction(boolean up, boolean down, boolean left, boolean right) throws Exception {
        checkFailure();
        int nextX = (right ? 1 : 0) - (left ? 1 : 0), nextY = (down ? 1 : 0) - (up ? 1 : 0);
        if (nextX == dx && nextY == dy && motion != null) return;
        dx = nextX; dy = nextY;
        rebuild();
    }

    synchronized void radiusKey(boolean pressed) throws Exception {
        checkFailure();
        if (switchMode == 1 && switched != pressed) { switched = pressed; if (dx != 0 || dy != 0) rebuild(); }
        else if (switchMode == 2 && pressed) { switched = !switched; if (dx != 0 || dy != 0) rebuild(); }
    }

    private void rebuild() throws Exception {
        if (closed) return;
        if (dx == 0 && dy == 0) { release(); return; }
        stopMotion(true);
        synchronized (state) {
            if (!state.down) {
                float x = centerX, y = centerY;
                if (startOffsetPx > 0) {
                    double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * startOffsetPx;
                    x += (float) (Math.cos(a) * r); y += (float) (Math.sin(a) * r);
                }
                x = JoystickTrajectory.clamp(Math.round(x), 0, width - 1);
                y = JoystickTrajectory.clamp(Math.round(y), 0, height - 1);
                state.place(x, y);
                touch.down(POINTER, x, y);
            }
        }
        final long token = generation;
        JoystickTrajectory.MoveSink sink = (id, x, y) -> {
            if (Thread.currentThread().isInterrupted() || generation != token) throw new InterruptedException();
            try { touch.move(id, x, y); }
            catch (Exception failure) { pendingFailure.compareAndSet(null, failure); throw failure; }
        };
        float radius = switched ? switchedRadius : baseRadius;
        float[] target = JoystickTrajectory.target(centerX, centerY, radius, dx, dy, params, random);
        Runnable task = new JoystickTrajectory.Motion(state, POINTER, target, width, height, params,
                new Random(random.nextLong()), sink, JoystickTrajectory.REAL_CLOCK, JoystickTrajectory.REAL_SLEEP);
        motion = worker.submit(() -> {
            try { task.run(); }
            catch (RuntimeException failure) { if (generation == token) pendingFailure.compareAndSet(null, failure); throw failure; }
        });
    }

    private void stopMotion(boolean propagateFailure) throws Exception {
        synchronized (state) {
            generation++;
            Future<?> previous = motion; motion = null;
            if (previous != null) {
                boolean cancelled = previous.cancel(true);
                if (propagateFailure && !cancelled && previous.isDone() && !previous.isCancelled()) readResult(previous);
            }
            if (propagateFailure) throwPendingFailure();
        }
    }

    private void release() throws Exception {
        stopMotion(false);
        synchronized (state) { if (state.down) touch.up(POINTER); state.clear(); }
    }

    synchronized void reset() throws Exception {
        dx = dy = 0; switched = false;
        try { release(); } finally { pendingFailure.set(null); }
    }

    synchronized void close() throws Exception {
        closed = true;
        try { reset(); } finally { worker.shutdownNow(); }
    }

    synchronized void checkFailure() throws Exception {
        throwPendingFailure();
        if (motion == null || !motion.isDone() || motion.isCancelled()) return;
        readResult(motion);
    }

    private void throwPendingFailure() {
        Throwable failure = pendingFailure.getAndSet(null);
        if (failure != null) throw new IllegalStateException("摇杆轨迹中断：" + failure, failure);
    }

    private static void readResult(Future<?> future) throws Exception {
        try { future.get(); }
        catch (CancellationException ignored) {}
        catch (ExecutionException error) { throw new IllegalStateException("摇杆轨迹中断：" + error.getCause(), error.getCause()); }
    }
}
