package cn.offline.mapper.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import cn.offline.mapper.BindingKey;
import cn.offline.mapper.ProfileStore;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dependency-free API tests. No activities, overlay, RootBridge, native loading or network. */
public final class MapperInstrumentation extends Instrumentation {
    private interface Check { void run() throws Exception; }
    private Context target;
    private SharedPreferences preferences;
    private int passed, failed;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        super.onStart();
        target = getTargetContext();
        preferences = target.getSharedPreferences("profiles-v1", Context.MODE_PRIVATE);
        Map<String, Object> original = snapshot(preferences.getAll());
        boolean restored = false;
        try {
            reset();
            suite();
        } catch (Throwable error) {
            failed++;
            report.append("HARNESS FAILURE: ").append(error).append('\n');
        } finally {
            try {
                restore(original);
                restored = original.equals(snapshot(preferences.getAll()));
                require(restored, "所有原 SharedPreferences 数据必须精确恢复");
            } catch (Throwable error) {
                failed++;
                report.append("RESTORE FAILURE: ").append(error).append('\n');
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + report + "\nMapper API tests: " + passed + " passed, " + failed + " failed; preferencesRestored=" + restored + "\n");
        result.putInt("passed", passed); result.putInt("failed", failed); result.putBoolean("preferencesRestored", restored);
        finish(failed == 0 && restored ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void suite() {
        check("template_and_default", () -> {
            reset(); JSONObject p = fresh(); ProfileStore.validate(p);
            equal(1, p.getInt("schema")); equal(5, p.getJSONArray("bindings").length());
            require(!p.getString("id").isEmpty(), "模板有 ID");
            require(!p.getString("id").equals(fresh().getString("id")), "模板 ID 唯一");
            equal(1, ProfileStore.all(target).length());
            equal("默认 FPS 配置", ProfileStore.current(target).getString("name"));
        });
        check("save_and_detached_current", () -> {
            reset(); JSONObject p = fresh(); ProfileStore.save(target, p);
            equal(p.getString("id"), ProfileStore.current(target).getString("id"));
            p.put("name", "外部修改"); equal("测试配置", ProfileStore.current(target).getString("name"));
            JSONObject current = ProfileStore.current(target); current.put("name", "返回值修改");
            equal("测试配置", ProfileStore.current(target).getString("name"));
        });
        check("rename_updates_existing", () -> {
            reset(); JSONObject p = fresh(); ProfileStore.save(target, p); int before = ProfileStore.all(target).length();
            p.put("name", "已重命名"); ProfileStore.save(target, p);
            equal(before, ProfileStore.all(target).length()); equal("已重命名", ProfileStore.current(target).getString("name"));
        });
        check("copy_is_deep_and_can_save_separately", () -> {
            reset(); JSONObject p = fresh(); ProfileStore.save(target, p);
            JSONObject copy = ProfileStore.copy(p); copy.put("id", UUID.randomUUID().toString()).put("name", "配置副本");
            copy.getJSONArray("bindings").getJSONObject(0).put("x", .25); ProfileStore.save(target, copy);
            equal(3, ProfileStore.all(target).length());
            ProfileStore.select(target, p.getString("id")); near(.87, ProfileStore.current(target).getJSONArray("bindings").getJSONObject(0).getDouble("x"));
            ProfileStore.select(target, copy.getString("id")); near(.25, ProfileStore.current(target).getJSONArray("bindings").getJSONObject(0).getDouble("x"));
        });
        check("switch_and_missing_selection_fallback", () -> {
            reset(); JSONObject a = fresh(), b = ProfileStore.template("第二配置", "game.example");
            ProfileStore.save(target, a); ProfileStore.save(target, b);
            ProfileStore.select(target, a.getString("id")); equal(a.getString("id"), ProfileStore.current(target).getString("id"));
            ProfileStore.select(target, b.getString("id")); equal("game.example", ProfileStore.current(target).getString("package"));
            ProfileStore.select(target, "not-present"); equal(ProfileStore.all(target).getJSONObject(0).getString("id"), ProfileStore.current(target).getString("id"));
        });
        check("delete_and_last_profile_fallback", () -> {
            reset(); JSONObject p = fresh(); ProfileStore.save(target, p); ProfileStore.delete(target, p.getString("id"));
            equal(1, ProfileStore.all(target).length());
            String finalId = ProfileStore.all(target).getJSONObject(0).getString("id"); ProfileStore.delete(target, finalId);
            equal(1, ProfileStore.all(target).length()); ProfileStore.validate(ProfileStore.current(target));
            require(!finalId.equals(ProfileStore.current(target).getString("id")), "删除最后配置后重新创建默认 ID");
        });
        check("json_roundtrip", () -> {
            JSONObject p = fresh(); JSONObject loaded = new JSONObject(p.toString(2)); ProfileStore.validate(loaded);
            equal(p.getString("id"), loaded.getString("id")); equal(p.getString("name"), loaded.getString("name"));
            near(p.getJSONArray("bindings").getJSONObject(0).getDouble("x"), loaded.getJSONArray("bindings").getJSONObject(0).getDouble("x"));
            equal(p.getJSONObject("joystick").getInt("up"), loaded.getJSONObject("joystick").getInt("up"));
        });
        check("duplicate_key_rejected", () -> {
            JSONObject p = fresh(); JSONArray b = p.getJSONArray("bindings"); b.getJSONObject(1).put("code", b.getJSONObject(0).getInt("code")); rejected(p);
        });
        check("duplicate_id_rejected", () -> {
            JSONObject p = fresh(); p.getJSONArray("bindings").getJSONObject(1).put("id", 1); rejected(p);
        });
        check("joystick_key_conflict_rejected", () -> {
            JSONObject p = fresh(); p.getJSONObject("joystick").put("up", 272); rejected(p);
        });
        check("legacy_joystick_profile_remains_loadable", () -> {
            JSONObject p=fresh(),j=p.getJSONObject("joystick");
            for(String name:new String[]{"radiusPixels","trajectoryMode","settleMs","wobblePx","overshootPercent","startOffsetPx","customAngles","angleMin","angleMax","radiusSwitchMode","radiusSwitchCode","radiusSwitchPercent"})j.remove(name);
            reset();ProfileStore.save(target,p);JSONObject restored=ProfileStore.current(target);
            require(!restored.getJSONObject("joystick").has("radiusPixels"),"旧比例布局不被悄悄替换为像素布局");
            near(.075,restored.getJSONObject("joystick").getDouble("radius"));
        });
        check("joystick_custom_angle_roundtrip_and_invalid_ranges", () -> {
            JSONObject p=fresh();p.getJSONObject("joystick").put("customAngles",true).put("angleMin",-45).put("angleMax",45);
            reset();ProfileStore.save(target,p);equal(-45,ProfileStore.current(target).getJSONObject("joystick").getInt("angleMin"));
            for(double bad:new double[]{.5}){p=fresh();p.getJSONObject("joystick").put("angleMin",bad);rejected(p);}
            p=fresh();p.getJSONObject("joystick").put("angleMin",5).put("angleMax",4);rejected(p);
        });
        check("joystick_radius_switch_conflicts_rejected", () -> {
            for(int bad:new int[]{-1,17,272,59,66,114,115,116}) {
                JSONObject p=fresh();p.getJSONObject("joystick").put("radiusSwitchMode",1).put("radiusSwitchCode",bad);rejected(p);
            }
            JSONObject p=fresh();p.getJSONObject("joystick").put("radiusSwitchMode",2).put("radiusSwitchCode",56);ProfileStore.validate(p);
        });
        check("joystick_radius_and_mode_limits", () -> {
            for(double bad:new double[]{29,301,100.5}){JSONObject p=fresh();p.getJSONObject("joystick").put("radiusSwitchPercent",bad);rejected(p);}
            for(int bad:new int[]{0,901}){JSONObject p=fresh();p.getJSONObject("joystick").put("radiusPixels",bad);rejected(p);}
            JSONObject p=fresh();p.getJSONObject("joystick").put("trajectoryMode",3);rejected(p);
            for(int bad:new int[]{9,501}){p=fresh();p.getJSONObject("joystick").put("settleMs",bad);rejected(p);}
            for(int bad:new int[]{-1,41}){p=fresh();p.getJSONObject("joystick").put("wobblePx",bad);rejected(p);}
            p=fresh();p.getJSONObject("joystick").put("trajectoryVariant",3).put("angleMin",-180).put("angleMax",180);ProfileStore.validate(p);
            require(!p.getJSONObject("joystick").has("trajectoryVariant"),"旧轨迹参数档被移除");equal(-45,p.getJSONObject("joystick").getInt("angleMin"));
            p=fresh();p.getJSONObject("joystick").put("radiusSwitchPercent",300).put("radiusPixels",900);ProfileStore.validate(p);
        });
        check("aim_key_conflict_rejected", () -> {
            JSONObject p = fresh(); p.getJSONObject("aim").put("toggleCode", 272); rejected(p);
        });
        for (int code : new int[] {66, 114, 115, 116}) {
            final int protectedCode = code;
            check("protected_key_" + code + "_rejected", () -> {
                JSONObject p = fresh(); p.getJSONArray("bindings").getJSONObject(0).put("code", protectedCode); rejected(p);
                p = fresh(); p.getJSONObject("joystick").put("up", protectedCode); rejected(p);
                p = fresh(); p.getJSONObject("aim").put("toggleCode", protectedCode); rejected(p);
            });
        }
        check("invalid_coordinates_rejected", () -> {
            for (double v : new double[] {-.001, 1.001}) {
                JSONObject p = fresh(); p.getJSONArray("bindings").getJSONObject(0).put("x", v); rejected(p);
                p = fresh(); p.getJSONObject("joystick").put("y", v); rejected(p);
                p = fresh(); JSONObject b = p.getJSONArray("bindings").getJSONObject(0); b.put("type", "swipe").put("endX", v); rejected(p);
            }
        });
        check("nan_infinity_and_missing_coordinate_rejected", () -> {
            // JSON disallows numeric NaN; strings represent the malformed import path to optDouble.
            for (String v : new String[] {"NaN", "Infinity", "-Infinity"}) {
                JSONObject p = fresh(); p.getJSONArray("bindings").getJSONObject(0).put("x", v); rejected(p);
            }
            JSONObject p = fresh(); p.getJSONArray("bindings").getJSONObject(0).remove("x"); rejected(p);
        });
        check("schema_and_unknown_action_rejected", () -> {
            JSONObject p = fresh(); p.put("schema", 2); rejected(p);
            p = fresh(); p.getJSONArray("bindings").getJSONObject(0).put("type", "unknown"); rejected(p);
        });
        check("schema_boundary_values_legal", () -> {
            JSONObject p = fresh(); p.getJSONObject("aim").put("sensitivityX", 30).put("sensitivityY", .05).put("idleMs", 0);
            JSONArray b = p.getJSONArray("bindings"); b.getJSONObject(0).put("duration", 16).put("interval", 30).put("x", 0).put("y", 1);
            b.getJSONObject(1).put("duration", 10000).put("interval", 10000);
            ProfileStore.validate(p);
            reset(); ProfileStore.save(target, p); JSONObject loaded = ProfileStore.current(target);
            near(30, loaded.getJSONObject("aim").getDouble("sensitivityX")); equal(0, loaded.getJSONObject("aim").getInt("idleMs"));
            equal(16, loaded.getJSONArray("bindings").getJSONObject(0).getInt("duration")); equal(10000, loaded.getJSONArray("bindings").getJSONObject(1).getInt("duration"));
        });
        check("capacity_100_atomic_rejection_and_update", () -> {
            reset(); JSONArray seed = new JSONArray(); JSONObject selected = null;
            for (int i = 0; i < 100; i++) { selected = ProfileStore.template("配置" + i, ""); seed.put(selected); }
            require(preferences.edit().putString("profiles", seed.toString()).putString("selected", selected.getString("id")).commit(), "容量测试准备成功");
            JSONObject last = ProfileStore.current(target); last.put("name", "100条时仍可更新"); ProfileStore.save(target, last); equal(100, ProfileStore.all(target).length());
            Map<String, Object> before = snapshot(preferences.getAll());
            try { ProfileStore.save(target, fresh()); throw new AssertionError("第101条必须拒绝"); } catch (IllegalArgumentException expected) { }
            equal(before, snapshot(preferences.getAll())); equal(100, ProfileStore.all(target).length());
        });
        check("binding_label_roundtrip", () -> {
            Set<Integer> codes = new HashSet<>();
            for (String label : BindingKey.labels()) { int code = BindingKey.codeFromLabel(label); require(code > 0, "可解析 " + label); require(codes.add(code), "标签码唯一 " + label); equal(label, BindingKey.label(code)); }
            equal(500, BindingKey.codeFromLabel(BindingKey.label(500))); equal(-1, BindingKey.codeFromLabel("not-a-key"));
            equal(57, BindingKey.codeFromLabel("SPACE")); equal(28, BindingKey.codeFromLabel("ENTER")); equal(272, BindingKey.codeFromLabel("LMB"));
        });
        check("android_linux_key_mapping", () -> {
            int[] letters = {30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
            for (int i = 0; i < letters.length; i++) equal(letters[i], BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_A + i));
            for (int i = 0; i < 10; i++) equal(59 + i, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_F1 + i));
            equal(87, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_F11)); equal(88, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_F12));
            equal(57, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_SPACE)); equal(66, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_F8)); equal(-1, BindingKey.linuxFromAndroid(KeyEvent.KEYCODE_UNKNOWN));
        });
        check("native_libraries_three_abis_have_correct_elf_headers", () -> {
            try (ZipFile apk = new ZipFile(target.getApplicationInfo().sourceDir)) {
                String[] abis = {"arm64-v8a", "armeabi-v7a", "x86_64"}; int[] machines = {183, 40, 62};
                for (int i = 0; i < abis.length; i++) {
                    ZipEntry entry = apk.getEntry("lib/" + abis[i] + "/libmapper_input.so"); require(entry != null && entry.getSize() > 20, "包含 " + abis[i] + " 输入引擎");
                    byte[] header = new byte[20];
                    try (InputStream in = apk.getInputStream(entry)) { int pos = 0, n; while (pos < header.length && (n = in.read(header, pos, header.length - pos)) > 0) pos += n; equal(20, pos); }
                    require(header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F', "ELF magic " + abis[i]);
                    equal(1, (int) header[5]); equal(machines[i], (header[18] & 255) | ((header[19] & 255) << 8));
                }
            }
        });
        check("manifest_has_no_internet_permission", () -> {
            PackageInfo info = target.getPackageManager().getPackageInfo(target.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) require(!"android.permission.INTERNET".equals(permission), "离线 app 不声明 INTERNET");
        });
    }

    private void check(String name, Check body) {
        Bundle status = new Bundle(); status.putString("class", getClass().getName()); status.putString("test", name); sendStatus(1, status);
        try { body.run(); passed++; report.append("PASS ").append(name).append('\n'); sendStatus(0, status); }
        catch (Throwable error) { failed++; report.append("FAIL ").append(name).append(": ").append(error).append('\n'); status.putString("stack", android.util.Log.getStackTraceString(error)); sendStatus(-2, status); }
    }
    private JSONObject fresh() { return ProfileStore.template("测试配置", "test.game.example"); }
    private void reset() { require(preferences.edit().clear().commit(), "测试配置隔离清理成功"); }
    private static void rejected(JSONObject profile) { try { ProfileStore.validate(profile); throw new AssertionError("配置应被拒绝"); } catch (IllegalArgumentException expected) { } }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual); }
    private static void near(double expected, double actual) { require(Math.abs(expected - actual) < .000001, "expected=" + expected + ", actual=" + actual); }
    private static Map<String, Object> snapshot(Map<String, ?> input) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) { Object value = entry.getValue(); if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value); copy.put(entry.getKey(), value); }
        return copy;
    }
    @SuppressWarnings("unchecked") private void restore(Map<String, Object> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) editor.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new IllegalStateException("无法恢复的 preferences 类型: " + key);
        }
        require(editor.commit(), "原配置恢复写入成功");
    }
}
