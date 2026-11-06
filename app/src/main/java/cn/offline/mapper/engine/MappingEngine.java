package cn.offline.mapper.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Deterministic mapping state, all coordinates use the current display orientation. */
final class MappingEngine {
    private static final class Binding {
        int id, code, duration, interval; String type;
        float x,y,endX,endY; long due, began; boolean repeating, swiping;
    }
    private final TouchSink touch;
    private final ArrayList<Binding> bindings = new ArrayList<>();
    private final HashMap<Integer, Set<Integer>> pressed = new HashMap<>();
    private int width=1920,height=1080;
    private JSONObject joystick = new JSONObject(), aim = new JSONObject();
    private JoystickController joystickController;
    private boolean running, aimLocked;
    private float aimX,aimY;
    private long lastAim;

    MappingEngine(TouchSink touch) { this.touch=touch; }
    boolean running() { return running; }
    boolean aimLocked() { return aimLocked; }
    boolean hasBinding(int code) { for(Binding b:bindings)if(b.code==code)return true;return false; }
    boolean freePointerButtons() { return aim.optBoolean("enabled",false) && !aimLocked; }
    void configure(JSONObject profile, int width, int height) throws Exception {
        if (width<1 || height<1 || width>16384 || height>16384) throw new IllegalArgumentException("屏幕尺寸无效");
        ArrayList<Binding> parsed=new ArrayList<>();
        HashSet<Integer> ids=new HashSet<>();
        JSONArray values=profile.optJSONArray("bindings");
        if (values!=null && values.length()>20) throw new IllegalArgumentException("映射数量最多20个");
        if (values!=null) for (int i=0;i<values.length();i++) {
            JSONObject value=values.getJSONObject(i); Binding b=new Binding();
            b.id=value.getInt("id"); b.code=value.getInt("code"); b.type=value.optString("type","hold");
            if(b.id<1 || b.id>20 || !ids.add(b.id)) throw new IllegalArgumentException("映射编号必须唯一并在1..20");
            if(b.code<1 || b.code>769 || b.code==66 || b.code==114 || b.code==115 || b.code==116)
                throw new IllegalArgumentException("映射键码无效或属于保护键");
            if(!("hold".equals(b.type)||"tap".equals(b.type)||"toggle".equals(b.type)||"repeat".equals(b.type)||"swipe".equals(b.type)))
                throw new IllegalArgumentException("不支持的映射类型: "+b.type);
            b.x=normalized(value,"x",.5f)*(width-1); b.y=normalized(value,"y",.5f)*(height-1);
            b.endX=normalized(value,"endX",value.optDouble("x",.5))*(width-1);
            b.endY=normalized(value,"endY",value.optDouble("y",.5))*(height-1);
            b.duration=bounded(value.optInt("duration",160),16,10000);
            b.interval=bounded(value.optInt("interval",100),30,10000); parsed.add(b);
        }
        JSONObject nextJoy=profile.optJSONObject("joystick"), nextAim=profile.optJSONObject("aim");
        validateRegion(nextJoy); validateRegion(nextAim);
        JoystickController.validateOptions(nextJoy);
        if(nextJoy!=null && nextJoy.optBoolean("enabled",false)) {
            HashSet<Integer> directionCodes=new HashSet<>();
            for(String name:new String[]{"up","down","left","right"}) {
                int code=nextJoy.optInt(name,name.equals("up")?17:name.equals("down")?31:name.equals("left")?30:32);
                if(code<1||code>769||code==66||code==114||code==115||code==116||!directionCodes.add(code))
                    throw new IllegalArgumentException("摇杆方向键无效或重复");
            }
            if(nextJoy.optInt("radiusSwitchMode",0)!=0 && !directionCodes.add(nextJoy.optInt("radiusSwitchCode")))
                throw new IllegalArgumentException("半径切换键不能与方向键重复");
            for(Binding binding:parsed)if(directionCodes.contains(binding.code))throw new IllegalArgumentException("摇杆按键与点位冲突");
            if(nextAim!=null && nextAim.optBoolean("enabled",false) && directionCodes.contains(nextAim.optInt("toggleCode",59)))
                throw new IllegalArgumentException("摇杆按键与视角切换键冲突");
        }
        pause(); this.width=width; this.height=height; bindings.clear(); bindings.addAll(parsed);
        joystick=nextJoy==null?new JSONObject():nextJoy; aim=nextAim==null?new JSONObject():nextAim;
        if(joystickController!=null)joystickController.close();
        joystickController=joystick.optBoolean("enabled",false)?new JoystickController(touch,joystick,profile.optString("package",""),width,height):null;
    }
    private static void validateRegion(JSONObject value) {
        if(value==null)return;
        normalized(value,"x",.5); normalized(value,"y",.5);
        double radius=value.optDouble("radius",.1);
        if(!Double.isFinite(radius) || radius<=0 || radius>.5) throw new IllegalArgumentException("控制区域半径无效");
        for(String key:new String[]{"sensitivityX","sensitivityY"}) {
            double sensitivity=value.optDouble(key,1);
            if(!Double.isFinite(sensitivity) || sensitivity<.05 || sensitivity>30) throw new IllegalArgumentException("灵敏度须在0.05..30");
        }
    }
    private static float normalized(JSONObject value,String key,double fallback) {
        double number=value.optDouble(key,fallback);
        if(!Double.isFinite(number)||number<0||number>1) throw new IllegalArgumentException("归一化坐标无效: "+key);
        return (float)number;
    }
    private static int bounded(int n,int min,int max) { return Math.max(min,Math.min(n,max)); }
    void start() { running=true; }
    void pause() throws Exception {
        running=false; aimLocked=false; pressed.clear();
        for(Binding b:bindings) { b.due=0;b.began=0;b.repeating=false;b.swiping=false; }
        try{if(joystickController!=null)joystickController.reset();}
        finally{touch.cancel();}
    }
    void cancel() throws Exception {
        boolean wasRunning=running; pause(); running=wasRunning;
    }
    private boolean held(int code) { for(Set<Integer> set:pressed.values())if(set.contains(code))return true;return false; }
    void key(int device,int code,int value,long now) throws Exception {
        if(!running)return;
        if(code==66 && value!=0) { pause(); return; }
        if(code==114||code==115||code==116)return;
        if(value==2)return;
        boolean before=held(code);
        Set<Integer> deviceKeys=pressed.get(device);
        if(deviceKeys==null){deviceKeys=new HashSet<>();pressed.put(device,deviceKeys);}
        if(value!=0)deviceKeys.add(code);else deviceKeys.remove(code);
        boolean after=held(code); if(before==after)return;
        if(aim.optBoolean("enabled",false) && code==aim.optInt("toggleCode",59) && after) {
            aimLocked=!aimLocked;
            if(!aimLocked)touch.up(31);
            for(Binding b:bindings)if(MouseCursor.button(b.code)!=0) {
                touch.up(b.id);b.due=0;b.repeating=false;b.swiping=false;
            }
            for(Set<Integer> set:pressed.values())set.removeIf(c->MouseCursor.button(c)!=0);
            return;
        }
        for(Binding b:bindings)if(b.code==code) {
            if("hold".equals(b.type)) { if(after)touch.down(b.id,b.x,b.y);else touch.up(b.id); }
            else if("toggle".equals(b.type) && after) { if(touch.contains(b.id))touch.up(b.id);else touch.down(b.id,b.x,b.y); }
            else if("repeat".equals(b.type)) {
                b.repeating=after;
                if(after){touch.down(b.id,b.x,b.y);b.began=now;b.due=now+Math.min(b.duration,Math.max(15,b.interval/2));}
                else {touch.up(b.id);b.due=0;}
            } else if(after && "tap".equals(b.type)) {
                touch.up(b.id);touch.down(b.id,b.x,b.y);b.due=now+b.duration;
            } else if(after && "swipe".equals(b.type)) {
                touch.up(b.id);touch.down(b.id,b.x,b.y);b.began=now;b.due=now+b.duration;b.swiping=true;
            }
        }
        updateJoystick(code,after);
    }
    void wheel(int device,int value,long now) throws Exception {
        int code=value>0?768:769;
        // One deterministic action for each notch; overlapping taps retain a single pointer.
        for(int i=0;i<Math.min(Math.abs(value),20);i++){key(device,code,1,now);key(device,code,0,now);}
    }
    private void updateJoystick(int code,boolean down) throws Exception {
        if(joystickController==null)return;
        if(code==joystick.optInt("up",17)||code==joystick.optInt("down",31)||code==joystick.optInt("left",30)||code==joystick.optInt("right",32))
            joystickController.direction(held(joystick.optInt("up",17)),held(joystick.optInt("down",31)),
                    held(joystick.optInt("left",30)),held(joystick.optInt("right",32)));
        if(joystick.optInt("radiusSwitchMode",0)!=0 && code==joystick.optInt("radiusSwitchCode",-1))joystickController.radiusKey(down);
    }
    void mouse(float dx,float dy,long now) throws Exception {
        if(!running||!aimLocked||(dx==0&&dy==0))return;
        float centerX=clampX((float)aim.optDouble("x",.65)*width),centerY=clampY((float)aim.optDouble("y",.5)*height);
        float radius=(float)aim.optDouble("radius",.2)*Math.min(width,height);
        if(!touch.contains(31)){aimX=centerX;aimY=centerY;touch.down(31,aimX,aimY);}
        float stepX=dx*(float)aim.optDouble("sensitivityX",1);
        float stepY=dy*(float)aim.optDouble("sensitivityY",1)*(aim.optBoolean("invertY",false)?-1:1);
        float nextX=aimX+stepX,nextY=aimY+stepY;
        if(Math.hypot(nextX-centerX,nextY-centerY)>radius) {
            // Lift and recenter at a stroke boundary so unlimited mouse travel remains possible.
            double length=Math.hypot(stepX,stepY);
            if(length>radius){stepX*=(float)(radius/length);stepY*=(float)(radius/length);}
            touch.up(31);aimX=centerX;aimY=centerY;touch.down(31,aimX,aimY);
            nextX=aimX+stepX;nextY=aimY+stepY;
        }
        aimX=clampX(nextX);aimY=clampY(nextY);touch.move(31,aimX,aimY);lastAim=now;
    }
    void tick(long now) throws Exception {
        if(!running)return;
        if(joystickController!=null)joystickController.checkFailure();
        for(Binding b:bindings) {
            if(b.swiping) {
                float progress=Math.min(1f,(float)(now-b.began)/b.duration);
                touch.move(b.id,b.x+(b.endX-b.x)*progress,b.y+(b.endY-b.y)*progress);
                if(now>=b.due){touch.up(b.id);b.swiping=false;b.due=0;}
            } else if(b.repeating) {
                if(touch.contains(b.id) && now>=b.due)touch.up(b.id);
                if(!touch.contains(b.id) && now>=b.began+b.interval) {
                    touch.down(b.id,b.x,b.y);b.began=now;b.due=now+Math.min(b.duration,Math.max(15,b.interval/2));
                }
            } else if(b.due>0 && now>=b.due) {touch.up(b.id);b.due=0;}
        }
        int idle=bounded(aim.optInt("idleMs",80),0,2000);
        if(idle>0 && touch.contains(31) && now-lastAim>=idle)touch.up(31);
    }
    private float clampX(float value){return Math.max(0,Math.min(width-1,value));}
    private float clampY(float value){return Math.max(0,Math.min(height-1,value));}
}
