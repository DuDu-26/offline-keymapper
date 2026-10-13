package cn.offline.mapper.engine;

import android.view.MotionEvent;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit app_process test entry. Uses an in-memory sink and never injects real input. */
public final class EngineSelfTest {
    private static final class Event {
        int action,index,count;long down,time;int[] ids;float[] x,y;
        Event(MotionEvent e) {
            action=e.getActionMasked();index=e.getActionIndex();count=e.getPointerCount();
            down=e.getDownTime();time=e.getEventTime();ids=new int[count];x=new float[count];y=new float[count];
            for(int i=0;i<count;i++){ids[i]=e.getPointerId(i);x[i]=e.getX(i);y[i]=e.getY(i);}
        }
    }
    private static void check(boolean result,String message) {if(!result)throw new AssertionError(message);}
    private static JSONObject binding(int id,int code,String type) throws Exception {
        return new JSONObject().put("id",id).put("code",code).put("type",type).put("x",.4).put("y",.5)
                .put("endX",.8).put("endY",.6).put("duration",100).put("interval",100);
    }
    public static void main(String[] ignored) throws Exception {
        ArrayList<Event> events=new ArrayList<>();
        TouchInjector touch=new TouchInjector(event->{events.add(new Event((MotionEvent)event));return true;});
        MappingEngine engine=new MappingEngine(touch);
        JSONObject profile=new JSONObject().put("bindings",new JSONArray().put(binding(1,57,"hold")).put(binding(2,28,"hold")))
                .put("joystick",new JSONObject().put("enabled",true).put("x",.2).put("y",.7).put("radius",.1))
                .put("aim",new JSONObject().put("enabled",true).put("x",.6).put("y",.5).put("radius",.2));
        engine.configure(profile,1000,600);engine.start();
        engine.key(1,57,1,1000);engine.key(1,28,1,1001);engine.key(1,57,0,1002);engine.key(1,28,0,1003);
        check(events.size()==4,"two holds event count");
        check(events.get(0).action==0&&events.get(1).action==5&&events.get(1).index==1,"second pointer down index");
        check(events.get(2).action==6&&events.get(2).index==0&&events.get(2).count==2,"up emitted before pointer removal");
        check(events.get(3).action==1&&events.get(3).ids[0]==events.get(1).ids[1],"remaining pointer ID stable");
        long down=events.get(0).down,prior=0;
        for(Event e:events){check(e.down==down,"gesture downTime stable");check(e.time>=prior,"monotonic eventTime");prior=e.time;}
        System.out.println("PASS multi-pointer actions, index, stable IDs and timestamps");
        events.clear();engine.key(1,17,1,1100);engine.key(1,32,1,1101);
        Event diagonal=events.get(events.size()-1);
        check(Math.abs(Math.hypot(diagonal.x[0]-200,diagonal.y[0]-420)-60)<.01,"diagonal radius normalization");
        engine.key(1,17,0,1102);engine.key(1,32,0,1103);check(!touch.contains(30),"joystick release");
        System.out.println("PASS joystick diagonal normalization and release");
        profile.put("bindings",new JSONArray().put(binding(1,57,"repeat")).put(binding(2,28,"swipe")).put(binding(3,46,"tap")));
        engine.configure(profile,1000,600);engine.start();events.clear();
        engine.key(1,57,1,2000);engine.tick(2050);check(!touch.contains(1),"repeat release half interval");
        engine.tick(2100);check(touch.contains(1),"repeat resumes at interval");engine.key(1,57,0,2101);engine.tick(2400);
        check(!touch.contains(1),"repeat stops on keyup");
        engine.key(1,28,1,2500);engine.tick(2550);engine.tick(2600);
        check(!touch.contains(2),"swipe finished and released");
        Event last=events.get(events.size()-1);check(last.action==1&&Math.abs(last.x[0]-799.2)<.01,"swipe ends at configured point");
        engine.key(1,46,1,2700);engine.key(1,46,0,2701);check(touch.contains(3),"tap survives physical release");engine.tick(2800);
        check(!touch.contains(3),"tap timer releases");
        System.out.println("PASS repeat stop, swipe completion and timed tap");
        engine.key(1,59,1,3000);check(engine.aimLocked(),"F1 locks aim");engine.mouse(20,10,3001);check(touch.contains(31),"aim touch");
        engine.tick(3100);check(!touch.contains(31)&&engine.aimLocked(),"aim idle releases touch without unlocking");
        engine.key(1,57,1,3200);engine.key(1,66,1,3201);
        check(!engine.running()&&!engine.aimLocked()&&!touch.contains(1),"F8 cancels everything");
        engine.start();engine.key(1,57,1,3300);engine.pause();
        check(!engine.running()&&!touch.contains(1),"disconnect pause clears pointers");
        System.out.println("PASS aim idle, F8 and disconnect cancellation");
        engine.configure(new JSONObject().put("bindings",new JSONArray().put(binding(1,57,"hold"))),1000,600);engine.start();
        engine.key(1,57,1,4000);engine.key(2,57,1,4001);engine.key(1,57,0,4002);
        check(touch.contains(1),"same key on second keyboard remains held");engine.key(2,57,0,4003);
        check(!touch.contains(1),"last keyboard release clears touch");
        System.out.println("PASS multiple keyboard ownership");
        profile=new JSONObject().put("bindings",new JSONArray().put(binding(1,57,"hold").put("x",1).put("y",1)))
                .put("aim",new JSONObject().put("enabled",true).put("x",.6).put("y",.5).put("radius",.2)
                        .put("sensitivityX",30).put("sensitivityY",30).put("idleMs",0));
        engine.configure(profile,1000,600);engine.start();engine.key(1,57,1,5000);
        Event edge=events.get(events.size()-1);check(edge.x[0]==999&&edge.y[0]==599,"100% coordinates stay on screen");
        engine.key(1,57,0,5001);engine.key(1,59,1,5002);engine.mouse(1000,1000,5003);engine.tick(15000);
        check(touch.contains(31),"idle0 retains aim touch");
        Event aimEdge=events.get(events.size()-1);check(Math.hypot(aimEdge.x[0]-600,aimEdge.y[0]-300)<=120.01,"high sensitivity clipped to aim radius");
        engine.pause();events.clear();
        MouseCursor cursor=new MouseCursor(touch);cursor.configure(1000,600);cursor.move(10000,-10000);
        check(cursor.x()==999&&cursor.y()==0,"cursor clamped");
        check(events.get(0).action==MotionEvent.ACTION_HOVER_MOVE,"cursor hover");
        cursor.key(273,1);cursor.key(273,0);cursor.scroll(1);
        check(events.get(1).action==MotionEvent.ACTION_DOWN&&events.get(2).action==MotionEvent.ACTION_BUTTON_PRESS,"mouse button press sequence");
        check(events.get(3).action==MotionEvent.ACTION_BUTTON_RELEASE&&events.get(4).action==MotionEvent.ACTION_UP,"mouse button release sequence");
        check(events.get(5).action==MotionEvent.ACTION_SCROLL,"mouse scroll");
        System.out.println("PASS screen edges, idle0, high sensitivity, cursor hover/buttons/scroll");
        events.clear();touch.down(20,100,100);touch.down(30,200,200);touch.down(31,300,300);
        int retained=events.get(2).ids[2];touch.up(30);touch.down(19,400,400);
        check(events.get(4).ids[1]==retained,"surviving high logical ID retains compact physical ID");
        for(Event e:events)for(int id:e.ids)check(id>=0&&id<10,"every framework pointer ID stays in0..9");
        touch.cancel();System.out.println("PASS compact physical IDs for logical20/30/31, stable reuse and release");
        System.out.println("ALL ENGINE SELF TESTS PASSED");
    }
}
