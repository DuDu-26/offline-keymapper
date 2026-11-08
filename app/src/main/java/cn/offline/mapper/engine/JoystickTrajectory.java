package cn.offline.mapper.engine;

import java.util.Random;

/**
 * Joystick thumb motion for keyboard direction input.
 *
 * <p>The thumb is modelled as a critically damped spring pulling toward a target
 * point at the edge of the joystick. Each direction change only swaps the target;
 * position and velocity carry over, so turns curve naturally instead of restarting.
 * The "natural" style adds a small randomised endpoint and a slow, continuous
 * value-noise drift while the key is held.
 *
 * <p>This class has no Android dependencies so it can be tested on a plain JVM.
 */
public final class JoystickTrajectory {
    public interface MoveSink { void move(int pointerId, int x, int y) throws Exception; }
    public interface Clock { long nanos(); }
    public interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    public static final Clock REAL_CLOCK = System::nanoTime;
    public static final Sleeper REAL_SLEEP = Thread::sleep;

    /** 0 natural (spring + drift), 1 smooth (spring only), 2 direct (jump to target). */
    public static final int NATURAL = 0, SMOOTH = 1, DIRECT = 2;
    public static final int FRAME_MS = 8;

    /** Shared thumb state; all access is synchronized on the instance. */
    public static final class State {
        public float x, y, vx, vy;
        public boolean down;
        public void place(float x, float y) { this.x = x; this.y = y; vx = vy = 0; down = true; }
        public void clear() { vx = vy = 0; down = false; }
    }

    /** Tunable parameters; all values are plain units shown in the editor. */
    public static final class Params {
        public final int style, settleMs, wobblePx, overshootPercent, angleMin, angleMax;
        public Params(int style, int settleMs, int wobblePx, int overshootPercent, int angleMin, int angleMax) {
            this.style = style; this.settleMs = settleMs; this.wobblePx = wobblePx;
            this.overshootPercent = overshootPercent; this.angleMin = angleMin; this.angleMax = angleMax;
        }
        public static Params defaults(int style) {
            switch (style) {
                case DIRECT: return new Params(DIRECT, 0, 0, 0, 0, 0);
                case SMOOTH: return new Params(SMOOTH, 60, 0, 10, 0, 0);
                default: return new Params(NATURAL, 70, 4, 15, -8, 8);
            }
        }
    }

    private JoystickTrajectory() {}

    /** Screen-space angle (radians, X right = 0, Y up positive) for a key direction. */
    public static double baseAngle(int dx, int dy) { return Math.atan2(-dy, dx); }

    /** Picks the target point for one direction hold. */
    public static float[] target(float cx, float cy, float radius, int dx, int dy, Params p, Random random) {
        double angle = baseAngle(dx, dy);
        double reach = radius * (1 + p.overshootPercent / 100.0);
        if (p.style == NATURAL) {
            if (p.angleMax > p.angleMin) angle += Math.toRadians(p.angleMin + random.nextDouble() * (p.angleMax - p.angleMin));
            else angle += Math.toRadians(p.angleMin);
            reach *= 0.95 + random.nextDouble() * 0.10;
        } else if (p.angleMin != 0 || p.angleMax != 0) {
            angle += Math.toRadians((p.angleMin + p.angleMax) / 2.0);
        }
        return new float[] {(float) (cx + Math.cos(angle) * reach), (float) (cy - Math.sin(angle) * reach)};
    }

    /** Smooth 1-D value noise in [-1, 1], with a new random knot every {@code periodMs}. */
    static final class Drift {
        private final Random random; private final float periodMs;
        private float from, to, phase;
        Drift(Random random, float periodMs) {
            this.random = random; this.periodMs = periodMs;
            from = 0; to = next();
        }
        private float next() { return random.nextFloat() * 2 - 1; }
        float advance(float ms) {
            phase += ms / periodMs;
            while (phase >= 1) { phase -= 1; from = to; to = next(); }
            float t = phase * phase * (3 - 2 * phase);
            return from + (to - from) * t;
        }
    }

    /**
     * Advances a critically damped spring by {@code dt} seconds toward {@code goal}.
     * Exact closed-form step, stable for any dt. Returns {position, velocity}.
     */
    static float[] spring(float position, float velocity, float goal, float omega, float dt) {
        float offset = position - goal;
        float decay = (float) Math.exp(-omega * dt);
        float term = (velocity + omega * offset) * dt;
        float nextVelocity = (velocity - omega * term) * decay;
        float nextOffset = (offset + term) * decay;
        return new float[] {goal + nextOffset, nextVelocity};
    }

    /** One direction hold. Runs until interrupted, or until settled for non-drifting styles. */
    public static final class Motion implements Runnable {
        private final State state; private final int pointerId;
        private final float targetX, targetY, minX, minY, maxX, maxY;
        private final Params params; private final MoveSink sink;
        private final Clock clock; private final Sleeper sleeper;
        private final Drift driftX, driftY;
        private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

        public Motion(State state, int pointerId, float[] target, int width, int height, Params params,
                      Random random, MoveSink sink, Clock clock, Sleeper sleeper) {
            this.state = state; this.pointerId = pointerId; this.params = params; this.sink = sink;
            this.clock = clock; this.sleeper = sleeper;
            minX = 0; minY = 0; maxX = width - 1; maxY = height - 1;
            targetX = clamp(target[0], minX, maxX); targetY = clamp(target[1], minY, maxY);
            boolean drift = params.style == NATURAL && params.wobblePx > 0;
            driftX = drift ? new Drift(random, 260 + random.nextInt(120)) : null;
            driftY = drift ? new Drift(random, 260 + random.nextInt(120)) : null;
            if (params.style == NATURAL) {
                synchronized (state) {
                    // A small sideways push so even a start from rest bends slightly, like a thumb.
                    float dx = targetX - state.x, dy = targetY - state.y;
                    float length = (float) Math.hypot(dx, dy);
                    if (length > 1) {
                        float kick = (random.nextFloat() * 2 - 1) * 0.35f * length * omega();
                        state.vx += -dy / length * kick; state.vy += dx / length * kick;
                    }
                }
            }
        }

        private float omega() { return 5000f / Math.max(1, params.settleMs); }

        @Override public void run() {
            try { loop(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }

        private void loop() throws Exception {
            if (params.style == DIRECT) {
                synchronized (state) {
                    if (Thread.currentThread().isInterrupted()) return;
                    state.x = targetX; state.y = targetY; state.vx = state.vy = 0;
                    emit();
                }
                return;
            }
            float omega = omega();
            long previous = clock.nanos();
            while (true) {
                sleeper.sleep(FRAME_MS);
                long now = clock.nanos();
                float ms = Math.max(1, Math.min(40, (now - previous) / 1_000_000f));
                previous = now;
                float dt = ms / 1000f;
                synchronized (state) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    float goalX = targetX, goalY = targetY;
                    if (driftX != null) {
                        goalX += driftX.advance(ms) * params.wobblePx;
                        goalY += driftY.advance(ms) * params.wobblePx;
                    }
                    float[] sx = spring(state.x, state.vx, goalX, omega, dt);
                    float[] sy = spring(state.y, state.vy, goalY, omega, dt);
                    state.x = clamp(sx[0], minX, maxX); state.vx = sx[1];
                    state.y = clamp(sy[0], minY, maxY); state.vy = sy[1];
                    boolean settled = driftX == null && Math.abs(state.x - goalX) < 0.5f && Math.abs(state.y - goalY) < 0.5f
                            && Math.abs(state.vx) < 5 && Math.abs(state.vy) < 5;
                    if (settled) { state.x = goalX; state.y = goalY; state.vx = state.vy = 0; }
                    emit();
                    if (settled) return;
                }
            }
        }

        private void emit() throws Exception {
            int x = Math.round(state.x), y = Math.round(state.y);
            if (x == lastX && y == lastY) return;
            lastX = x; lastY = y;
            sink.move(pointerId, x, y);
        }
    }

    static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
