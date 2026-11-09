package cn.offline.mapper.engine;

import org.json.JSONObject;

/** Validation for the optional mouse-view settings, shared by the engine and the profile store. */
public final class AimOptions {
    /** Send rates offered in the editor, matching common mouse report rates; 0 forwards every report as it arrives. */
    public static final int[] RATES = {0, 125, 250, 500, 1000};

    private AimOptions() {}

    public static void validate(JSONObject value) {
        if (value == null) return;
        double rate = value.optDouble("rateHz", 0);
        if (rate != Math.rint(rate) || rate < 0 || rate > 1000 || (rate > 0 && rate < 30))
            throw new IllegalArgumentException("视角发送频率须为 0 或 30～1000");
        double scale = value.optDouble("scopeScale", 1);
        if (!Double.isFinite(scale) || scale < .05 || scale > 5) throw new IllegalArgumentException("开镜灵敏度倍率须在 0.05～5");
        int mode = value.optInt("scopeMode", 0), code = value.optInt("scopeCode", 0);
        if (mode != 0 && mode != 1) throw new IllegalArgumentException("开镜方式无效");
        if (code != 0 && (code < 1 || code > 769 || code == 66 || code == 114 || code == 115 || code == 116 || code == value.optInt("toggleCode", 59)))
            throw new IllegalArgumentException("开镜键无效或与视角切换键相同");
    }
}
