package cn.offline.mapper;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
import cn.offline.mapper.engine.JoystickController;

public final class ProfileStore {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("profiles-v1",Context.MODE_PRIVATE);}
    public static synchronized JSONArray all(Context c){
        try { JSONArray a=new JSONArray(prefs(c).getString("profiles","[]")); if(a.length()>0)return a; } catch(JSONException ignored){}
        JSONArray a=new JSONArray();a.put(template("默认 FPS 配置","")); prefs(c).edit().putString("profiles",a.toString()).commit();return a;
    }
    public static synchronized JSONObject current(Context c){JSONArray a=all(c);String id=prefs(c).getString("selected","");for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p.optString("id").equals(id))return copy(p);}return copy(a.optJSONObject(0));}
    public static JSONObject copy(JSONObject p){try{return new JSONObject(p.toString());}catch(JSONException e){throw new IllegalArgumentException(e);}}
    public static synchronized void select(Context c,String id){prefs(c).edit().putString("selected",id).commit();}
    public static synchronized void save(Context c,JSONObject profile){
        validate(profile);JSONArray a=all(c);JSONArray b=new JSONArray();boolean found=false;
        for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p.optString("id").equals(profile.optString("id"))){b.put(copy(profile));found=true;}else b.put(p);}
        if(!found)b.put(copy(profile)); if(b.length()>100)throw new IllegalArgumentException("配置数量不能超过100");
        if(!prefs(c).edit().putString("profiles",b.toString()).putString("selected",profile.optString("id")).commit())throw new IllegalStateException("配置保存失败");
    }
    public static synchronized void delete(Context c,String id){JSONArray a=all(c),b=new JSONArray();for(int i=0;i<a.length();i++)if(!a.optJSONObject(i).optString("id").equals(id))b.put(a.optJSONObject(i));if(b.length()==0)b.put(template("默认 FPS 配置",""));prefs(c).edit().putString("profiles",b.toString()).putString("selected",b.optJSONObject(0).optString("id")).commit();}
    public static JSONObject template(String name,String pkg){
        try{
            JSONObject p=new JSONObject();p.put("schema",1).put("id",UUID.randomUUID().toString()).put("name",name).put("package",pkg);
            JSONArray a=new JSONArray();a.put(binding(1,"hold",272,0.87,0.48));a.put(binding(2,"hold",273,0.75,0.35));a.put(binding(3,"tap",57,0.85,0.78));a.put(binding(4,"tap",19,0.7,0.78));a.put(binding(5,"hold",42,0.18,0.48));p.put("bindings",a);
            p.put("joystick",new JSONObject().put("enabled",true).put("x",0.18).put("y",0.75).put("radius",0.075).put("radiusPixels",90)
                    .put("trajectoryMode",0).put("trajectoryVariant",-1).put("customAngles",false).put("angleMin",-12).put("angleMax",12)
                    .put("radiusSwitchMode",0).put("radiusSwitchCode",56).put("radiusSwitchPercent",100)
                    .put("up",17).put("down",31).put("left",30).put("right",32));
            p.put("aim",new JSONObject().put("enabled",true).put("x",0.65).put("y",0.50).put("radius",0.2).put("sensitivityX",1).put("sensitivityY",1).put("toggleCode",59).put("idleMs",80).put("invertY",false));return p;
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    public static JSONObject binding(int id,String type,int code,double x,double y){try{return new JSONObject().put("id",id).put("type",type).put("code",code).put("label",BindingKey.label(code)).put("x",x).put("y",y).put("endX",Math.min(0.98,x+0.12)).put("endY",y).put("duration",160).put("interval",100);}catch(JSONException e){throw new IllegalStateException(e);}}
    public static void validate(JSONObject p){
        if(p==null||p.optInt("schema",1)!=1)throw new IllegalArgumentException("不支持的配置格式");
        if(p.optString("id").isEmpty()||p.optString("id").length()>128||p.optString("name").trim().isEmpty()||p.optString("name").length()>80)throw new IllegalArgumentException("配置名称或ID无效");
        JSONArray b=p.optJSONArray("bindings");if(b==null||b.length()>20)throw new IllegalArgumentException("最多20个按键点位");Set<Integer> ids=new HashSet<>();Set<Integer> codes=new HashSet<>();
        for(int i=0;i<b.length();i++){JSONObject n=b.optJSONObject(i);if(n==null)throw new IllegalArgumentException("按键格式错误");int id=n.optInt("id",0),code=n.optInt("code",-1);if(id<1||id>20||!ids.add(id))throw new IllegalArgumentException("按键ID无效或重复");checkCode(code);if(code==66||!codes.add(code))throw new IllegalArgumentException("按键重复或使用了紧急暂停键F8");String t=n.optString("type");if(!Arrays.asList("hold","tap","toggle","repeat","swipe").contains(t))throw new IllegalArgumentException("不支持的按键类型");checkXY(n);if(t.equals("swipe")){range(n,"endX",0,1);range(n,"endY",0,1);}range(n,"duration",16,10000);range(n,"interval",30,10000);}
        JSONObject j=p.optJSONObject("joystick");if(j!=null){checkXY(j);range(j,"radius",0.01,0.45);JoystickController.validateOptions(j);if(j.optBoolean("enabled")){
            for(String key:new String[]{"up","down","left","right"}){int code=j.optInt(key);checkCode(code);if(code==66||!codes.add(code))throw new IllegalArgumentException("摇杆按键重复或与点位冲突");}
            if(j.optInt("radiusSwitchMode",0)!=0){int code=j.optInt("radiusSwitchCode",-1);checkCode(code);if(code==66||!codes.add(code))throw new IllegalArgumentException("半径切换键与其他按键冲突");}
        }}
        JSONObject a=p.optJSONObject("aim");if(a!=null){checkXY(a);range(a,"radius",0.02,0.45);range(a,"sensitivityX",0.05,30);range(a,"sensitivityY",0.05,30);range(a,"idleMs",0,2000);int code=a.optInt("toggleCode",59);checkCode(code);if(a.optBoolean("enabled")&&(code==66||!codes.add(code)))throw new IllegalArgumentException("视角切换键与其他按键冲突");}
    }
    private static void checkCode(int c){if(c<1||c>769||c==114||c==115||c==116)throw new IllegalArgumentException("按键码无效");}
    private static void checkXY(JSONObject n){range(n,"x",0,1);range(n,"y",0,1);}
    private static void range(JSONObject n,String key,double min,double max){double v=n.optDouble(key,Double.NaN);if(Double.isNaN(v)||Double.isInfinite(v)||v<min||v>max)throw new IllegalArgumentException(key+" 数值应在 "+min+"～"+max+" 之间");}
    private ProfileStore(){}
}
