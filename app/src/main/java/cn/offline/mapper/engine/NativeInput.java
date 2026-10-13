package cn.offline.mapper.engine;

/** Root-only evdev bridge. Each poll record is [device, type, code, value]. */
public final class NativeInput {
    private NativeInput() {}
    public static native boolean setGrab(boolean enabled);
    public static native int[] poll(int timeoutMillis);
    public static native String devices();
    public static native String error();
    public static native void close();
}
