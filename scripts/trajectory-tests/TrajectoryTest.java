package cn.offline.mapper.engine;

import java.util.ArrayList;
import java.util.Random;

/** Plain-JVM checks for JoystickTrajectory; run with scripts/test-trajectory.sh. */
public final class TrajectoryTest {
    private static int failures;
    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    /** Fake time: every sleep advances the clock exactly, so runs are deterministic. */
    private static final class FakeTime implements JoystickTrajectory.Clock, JoystickTrajectory.Sleeper {
        long nanos; int sleeps; final int limit;
        FakeTime(int limit) { this.limit = limit; }
        public long nanos() { return nanos; }
        public void sleep(long ms) throws InterruptedException {
            if (++sleeps > limit) throw new InterruptedException();
            nanos += ms * 1_000_000L;
        }
    }

    private static ArrayList<int[]> run(JoystickTrajectory.State state, int dx, int dy, JoystickTrajectory.Params p,
                                        long seed, int frames, float radius) {
        ArrayList<int[]> points = new ArrayList<>();
        Random random = new Random(seed);
        float[] target = JoystickTrajectory.target(500, 500, radius, dx, dy, p, random);
        FakeTime time = new FakeTime(frames);
        new JoystickTrajectory.Motion(state, 30, target, 1000, 1000, p, random,
                (id, x, y) -> points.add(new int[] {x, y, time.sleeps}), time, time).run();
        Thread.interrupted();
        return points;
    }

    public static void main(String[] args) {
        float radius = 90;
        JoystickTrajectory.Params smooth = JoystickTrajectory.Params.defaults(JoystickTrajectory.SMOOTH);
        JoystickTrajectory.Params natural = JoystickTrajectory.Params.defaults(JoystickTrajectory.NATURAL);
        JoystickTrajectory.Params direct = JoystickTrajectory.Params.defaults(JoystickTrajectory.DIRECT);

        // Direct: a single MOVE exactly at the edge (plus overshoot 0).
        JoystickTrajectory.State s = new JoystickTrajectory.State(); s.place(500, 500);
        ArrayList<int[]> pts = run(s, 1, 0, direct, 1, 100, radius);
        check(pts.size() == 1 && pts.get(0)[0] == 590 && pts.get(0)[1] == 500, "direct right jumps to edge in one move");
        s.place(500, 500); pts = run(s, 1, -1, direct, 1, 100, radius);
        check(pts.size() == 1 && pts.get(0)[0] == 564 && pts.get(0)[1] == 436, "direct up-right uses the diagonal");

        // Smooth: reaches target within roughly settle time and then stops emitting.
        s.place(500, 500); pts = run(s, 0, -1, smooth, 2, 400, radius);
        int[] last = pts.get(pts.size() - 1);
        int expectedY = Math.round(500 - radius * 1.10f);
        check(last[0] == 500 && last[1] == expectedY, "smooth up settles on target");
        int reachFrame = -1;
        for (int[] p : pts) if (Math.abs(p[1] - expectedY) <= 3) { reachFrame = p[2]; break; }
        check(reachFrame > 0 && reachFrame * JoystickTrajectory.FRAME_MS <= smooth.settleMs * 1.6, "smooth reaches within ~settle time (" + reachFrame * 8 + "ms)");
        check(last[2] < 400, "smooth stops once settled");
        boolean monotonic = true; int prev = 500;
        for (int[] p : pts) { if (p[1] > prev) monotonic = false; prev = p[1]; }
        check(monotonic, "critically damped: no overshoot past the target");

        // Turning keeps velocity: path from up to right should bend, not jump.
        s.place(500, 500); run(s, 0, -1, smooth, 3, 3, radius);
        pts = run(s, 1, 0, smooth, 3, 400, radius);
        int maxStep = 0; int[] before = null;
        for (int[] p : pts) { if (before != null) maxStep = Math.max(maxStep, Math.abs(p[0] - before[0]) + Math.abs(p[1] - before[1])); before = p; }
        check(maxStep < radius, "turn is continuous (largest step " + maxStep + "px)");

        // Natural: random endpoint within the angle range, keeps drifting while held, stays near target.
        s.place(500, 500); pts = run(s, 1, 0, natural, 4, 1000, radius);
        check(pts.size() > 40, "natural keeps emitting slow drift while held (" + pts.size() + " moves)");
        boolean near = true;
        for (int i = pts.size() / 2; i < pts.size(); i++) {
            int[] p = pts.get(i);
            double angle = Math.toDegrees(Math.atan2(-(p[1] - 500), p[0] - 500));
            double dist = Math.hypot(p[0] - 500, p[1] - 500);
            if (Math.abs(angle) > natural.angleMax + 6 || dist < radius || dist > radius * 1.3 + natural.wobblePx + 2) near = false;
        }
        check(near, "natural stays within angle range and pushes past the radius");
        boolean distinct = false;
        for (long seed = 10; seed < 20; seed++) {
            JoystickTrajectory.State a = new JoystickTrajectory.State(); a.place(500, 500);
            JoystickTrajectory.State b = new JoystickTrajectory.State(); b.place(500, 500);
            int[] pa = run(a, 1, 0, natural, seed, 30, radius).get(10), pb = run(b, 1, 0, natural, seed + 100, 30, radius).get(10);
            if (pa[0] != pb[0] || pa[1] != pb[1]) distinct = true;
        }
        check(distinct, "natural paths differ between holds");
        JoystickTrajectory.State a = new JoystickTrajectory.State(); a.place(500, 500);
        JoystickTrajectory.State b = new JoystickTrajectory.State(); b.place(500, 500);
        ArrayList<int[]> ra = run(a, -1, 1, natural, 77, 200, radius), rb = run(b, -1, 1, natural, 77, 200, radius);
        boolean same = ra.size() == rb.size();
        for (int i = 0; same && i < ra.size(); i++) same = ra.get(i)[0] == rb.get(i)[0] && ra.get(i)[1] == rb.get(i)[1];
        check(same, "same seed is reproducible");

        // Screen clamp near the edge.
        s.place(5, 5); pts = run(s, -1, -1, natural, 5, 300, radius);
        boolean inside = true; for (int[] p : pts) if (p[0] < 0 || p[1] < 0 || p[0] > 999 || p[1] > 999) inside = false;
        check(inside, "positions are clamped to the screen");

        // Interruption: no MOVE after the thread is interrupted.
        s.place(500, 500);
        ArrayList<int[]> after = new ArrayList<>();
        Thread.currentThread().interrupt();
        Random r = new Random(6);
        new JoystickTrajectory.Motion(s, 30, JoystickTrajectory.target(500, 500, radius, 1, 0, natural, r), 1000, 1000, natural, r,
                (id, x, y) -> after.add(new int[] {x, y}), JoystickTrajectory.REAL_CLOCK, ms -> {}).run();
        check(after.isEmpty() && Thread.interrupted(), "interrupted motion emits nothing");

        if (failures > 0) { System.out.println(failures + " TRAJECTORY TEST(S) FAILED"); System.exit(1); }
        System.out.println("ALL TRAJECTORY TESTS PASSED");
    }
}
