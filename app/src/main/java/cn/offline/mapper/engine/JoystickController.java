package cn.offline.mapper.engine;

import android.graphics.Point;
import org.json.JSONObject;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** WASD state reconstructed from r3.c, j3.h and b4.j1 of the supplied artifact. */
public final class JoystickController {
    private final TouchSink touch;
    private final Point center, current=new Point(-1,-1);
    private final int width,height,switchMode,mode,variant,angleMin,angleMax;
    private final boolean customAngles;
    private final float baseRadius,switchedRadius;
    private final String gamePackage;
    private final Random targetRandom=new Random(), downRandom=new Random();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"mapper-joystick");thread.setDaemon(true);return thread;
    });
    private Future<?> motion;
    private final AtomicReference<Throwable> pendingFailure=new AtomicReference<>();
    private volatile long generation;
    private boolean active,switched,closed;
    private int dx,dy;

    JoystickController(TouchSink touch,JSONObject j,String gamePackage,int width,int height) {
        validateOptions(j);
        this.touch=touch;this.width=width;this.height=height;this.gamePackage=gamePackage;
        center=new Point(Math.min(width-1,(int)(j.optDouble("x",.18)*width)),
                Math.min(height-1,(int)(j.optDouble("y",.75)*height)));
        int radius=j.has("radiusPixels")?j.optInt("radiusPixels"):
                (int)(j.optDouble("radius",.075)*Math.min(width,height));
        baseRadius=radius;
        switchedRadius=(radius*j.optInt("radiusSwitchPercent",100))/100;
        switchMode=j.optInt("radiusSwitchMode",0);
        mode=j.optInt("trajectoryMode",0);variant=j.optInt("trajectoryVariant",-1);
        customAngles=j.optBoolean("customAngles",false);
        angleMin=j.optInt("angleMin",-12);angleMax=j.optInt("angleMax",12);
    }
    public static void validateOptions(JSONObject j) {
        if(j==null)return;
        integer(j,"trajectoryMode",0,0,2);
        integer(j,"trajectoryVariant",-1,-1,3);
        integer(j,"angleMin",-12,-180,180);integer(j,"angleMax",12,-180,180);
        if(j.optInt("angleMin",-12)>j.optInt("angleMax",12))throw new IllegalArgumentException("角度下限不能大于上限");
        integer(j,"radiusSwitchMode",0,0,2);
        integer(j,"radiusSwitchPercent",100,30,300);
        if(j.has("radiusPixels"))integer(j,"radiusPixels",90,1,900);
        if(j.optInt("radiusSwitchMode",0)!=0) {
            int code=j.optInt("radiusSwitchCode",-1);
            if(code<1||code>769||code==66||code==114||code==115||code==116)
                throw new IllegalArgumentException("请设置有效的半径切换键");
        }
    }
    private static void integer(JSONObject j,String name,int fallback,int min,int max) {
        double value=j.optDouble(name,fallback);
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<min||value>max)
            throw new IllegalArgumentException(name+" 须为 "+min+"～"+max+" 的整数");
    }
    synchronized void direction(boolean up,boolean down,boolean left,boolean right) throws Exception {
        checkFailure();
        dx=(right?1:0)-(left?1:0);dy=(down?1:0)-(up?1:0);
        rebuild();
    }
    synchronized void radiusKey(boolean pressed) throws Exception {
        checkFailure();
        if(switchMode==1 && switched!=pressed){switched=pressed;if(dx!=0||dy!=0)rebuild();}
        else if(switchMode==2 && pressed){switched=!switched;if(dx!=0||dy!=0)rebuild();}
    }
    private void rebuild() throws Exception {
        if(closed)return;
        if(dx==0&&dy==0){release();return;}
        if(!active) {
            synchronized(current) {
                int x=center.x,y=center.y;
                if(mode!=2){x+=downRandom.nextInt(60)-30;y+=downRandom.nextInt(60)-30;}
                current.set(x,y);touch.down(30,x,y);active=true;
            }
        }
        stopMotion(true);
        final long token=generation;
        JoystickTrajectory.MoveSink sink=(id,x,y)->{
            if(Thread.currentThread().isInterrupted()||generation!=token)throw new InterruptedException();
            try{touch.move(id,x,y);}
            catch(Exception failure){pendingFailure.compareAndSet(null,failure);throw failure;}
        };
        int direction=dy<0?(dx==0?1:dx>0?5:8):dy>0?(dx==0?2:dx>0?6:7):(dx>0?3:4);
        float radius=switched?switchedRadius:baseRadius;
        Runnable task;
        if(mode==2) task=JoystickTrajectory.direct(current,center,radius,direction,30,sink);
        else {
            boolean enhancedGame="com.tencent.tmgp.codev".equals(gamePackage);
            int internalMode=mode==1?(enhancedGame?0:2):(enhancedGame?1:0);
            boolean twoPhase=mode==1&&"com.tencent.tmgp.pubgmhd".equals(gamePackage);
            if(twoPhase)internalMode=0;
            // Original package exception: j3.d.u, p.abcdstr(5442), observed read-only.
            if(variant>=0){internalMode=variant==3?0:variant;twoPhase=variant==3;}
            JoystickTrajectory.Params params=JoystickTrajectory.preset(internalMode,twoPhase);
            if(customAngles)params=params.withAngles(angleMin,angleMax);
            task=JoystickTrajectory.random(current,center,radius,direction,30,width,height,
                    params,targetRandom,new Random(),sink,JoystickTrajectory.REAL_SLEEP);
        }
        motion=worker.submit(()->{
            try{task.run();}
            catch(RuntimeException failure){if(generation==token)pendingFailure.compareAndSet(null,failure);throw failure;}
        });
    }
    private void stopMotion(boolean propagateFailure) throws Exception {
        synchronized(current) {
            generation++;
            Future<?> previous=motion;motion=null;
            if(previous!=null) {
                boolean cancelled=previous.cancel(true);
                if(propagateFailure&&!cancelled&&previous.isDone()&&!previous.isCancelled())readResult(previous);
            }
            if(propagateFailure)throwPendingFailure();
        }
    }
    private void release() throws Exception {
        stopMotion(false);
        synchronized(current){if(active)touch.up(30);active=false;current.set(-1,-1);}
    }
    synchronized void reset() throws Exception {
        dx=dy=0;switched=false;
        try{release();}finally{pendingFailure.set(null);}
    }
    synchronized void close() throws Exception {
        closed=true;try{reset();}finally{worker.shutdownNow();}
    }
    synchronized void checkFailure() throws Exception {
        throwPendingFailure();
        if(motion==null||!motion.isDone()||motion.isCancelled())return;
        readResult(motion);
    }
    private void throwPendingFailure() {
        Throwable failure=pendingFailure.getAndSet(null);
        if(failure!=null)throw new IllegalStateException("摇杆轨迹中断："+failure,failure);
    }
    private static void readResult(Future<?> future) throws Exception {
        try{future.get();}
        catch(CancellationException ignored){}
        catch(ExecutionException error){throw new IllegalStateException("摇杆轨迹中断："+error.getCause(),error.getCause());}
    }
}
