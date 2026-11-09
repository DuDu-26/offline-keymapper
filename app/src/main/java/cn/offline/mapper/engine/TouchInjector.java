package cn.offline.mapper.engine;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;

/** A single coherent Android gesture stream, shared by every mapping. */
final class TouchInjector implements TouchSink {
    interface InjectionSink { boolean inject(InputEvent event) throws Exception; }
    private static final class Point {
        final int logicalId, pointerId; final float size; float x, y;
        Point(int logicalId, int pointerId, float x, float y, float size) { this.logicalId = logicalId; this.pointerId = pointerId; this.x = x; this.y = y; this.size = size; }
    }
    private final ArrayList<Point> points = new ArrayList<>();
    private final Object inputManager;
    private final Method inject;
    private final InjectionSink testSink;
    private long downTime;
    private long eventTime;
    private final Random sizeRandom = new Random();
    // Reused for every event: the framework copies these into the MotionEvent, so no per-event garbage.
    private final MotionEvent.PointerProperties[][] propertyPool = new MotionEvent.PointerProperties[11][];
    private final MotionEvent.PointerCoords[][] coordPool = new MotionEvent.PointerCoords[11][];

    TouchInjector() throws Exception {
        testSink=null;
        Object manager = null; Method method = null; Exception failure = null;
        for (String name : new String[] {"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"}) {
            try {
                Class<?> cls = Class.forName(name);
                manager = cls.getDeclaredMethod("getInstance").invoke(null);
                method = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
                if (manager != null) break;
            } catch (Exception e) { failure = e; }
        }
        if (manager == null || method == null) throw new IllegalStateException("系统输入注入接口不可用", failure);
        inputManager = manager; inject = method;
    }
    TouchInjector(InjectionSink sink) { inputManager=null;inject=null;testSink=sink; }

    public synchronized boolean contains(int id) { return find(id) >= 0; }
    private int find(int id) {
        for (int i = 0; i < points.size(); i++) if (points.get(i).logicalId == id) return i;
        return -1;
    }
    public synchronized void down(int id, float x, float y) throws Exception {
        if (id < 0 || id > 31) throw new IllegalArgumentException("触点编号必须在 0..31");
        if (contains(id)) { move(id, x, y); return; }
        if (points.size() >= 10) throw new IllegalStateException("同时触点超过10个");
        if (points.isEmpty()) downTime = SystemClock.uptimeMillis();
        // Frameworks may index internal transform arrays by pointer ID.
        // Keep logical mapping IDs separate from compact Android IDs 0..9.
        boolean[] used = new boolean[10];
        for (Point point : points) used[point.pointerId] = true;
        int pointerId = 0; while (used[pointerId]) pointerId++;
        // Each finger keeps one plausible contact size for its whole gesture.
        points.add(new Point(id, pointerId, x, y, 0.03f + sizeRandom.nextFloat() * 0.015f));
        int action = points.size() == 1 ? MotionEvent.ACTION_DOWN
                : MotionEvent.ACTION_POINTER_DOWN | ((points.size() - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        emit(action);
    }
    public synchronized void move(int id, float x, float y) throws Exception {
        int i = find(id); if (i < 0) return;
        Point p = points.get(i);
        p.x = x; p.y = y; emit(MotionEvent.ACTION_MOVE);
    }
    public synchronized void up(int id) throws Exception {
        int index = find(id); if (index < 0) return;
        int action = points.size() == 1 ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        emit(action);
        points.remove(index);
    }
    public synchronized void cancel() throws Exception {
        try { if (!points.isEmpty()) emit(MotionEvent.ACTION_CANCEL); }
        finally { points.clear(); downTime = 0; }
    }
    synchronized void injectEvent(InputEvent event) throws Exception {
        Object result=testSink==null?inject.invoke(inputManager,event,0):testSink.inject(event);
        if(Boolean.FALSE.equals(result))throw new IllegalStateException("系统拒绝输入注入");
    }
    private void emit(int action) throws Exception {
        int count = points.size();
        if (propertyPool[count] == null) {
            propertyPool[count] = new MotionEvent.PointerProperties[count];
            coordPool[count] = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                propertyPool[count][i] = new MotionEvent.PointerProperties();
                propertyPool[count][i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordPool[count][i] = new MotionEvent.PointerCoords();
                coordPool[count][i].pressure = 1f;
            }
        }
        MotionEvent.PointerProperties[] properties = propertyPool[count];
        MotionEvent.PointerCoords[] coordinates = coordPool[count];
        for (int i = 0; i < count; i++) {
            Point p = points.get(i);
            properties[i].id = p.pointerId;
            coordinates[i].x = p.x; coordinates[i].y = p.y; coordinates[i].size = p.size;
        }
        eventTime = Math.max(eventTime, SystemClock.uptimeMillis());
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, points.size(),
                properties, coordinates, 0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            injectEvent(event);
        } finally { event.recycle(); }
    }
}
