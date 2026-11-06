package oracle;

import android.graphics.Point;
import dalvik.system.DexClassLoader;
import java.lang.reflect.*;
import java.util.*;
import moe.heroman.eventtest.auth.NativeAuth;
import org.json.*;

/** Runs the unmodified recovered DEX calculation classes behind test-only adapters. */
public final class TrajectoryOracle {
    private static ClassLoader original;
    private static Class<?> originalGenerator,originalMotion;
    private static final int DURATION=37,STEPS=22;

    public static final class RecordingRandom extends Random {
        public final ArrayList<String> calls=new ArrayList<>();
        public RecordingRandom(long seed){super(seed);}
        @Override public int nextInt(int n){int v=super.nextInt(n);calls.add("i:"+n+":"+v);return v;}
        @Override public float nextFloat(){float v=super.nextFloat();calls.add("f:"+Integer.toHexString(Float.floatToIntBits(v)));return v;}
        @Override public double nextDouble(){double v=super.nextDouble();calls.add("d:"+Long.toHexString(Double.doubleToLongBits(v)));return v;}
        @Override public boolean nextBoolean(){boolean v=super.nextBoolean();calls.add("b:"+v);return v;}
    }
    private static Object field(Object o,String name)throws Exception{return o.getClass().getField(name).get(o);}
    private static void set(Object o,String name,Object v)throws Exception{Field f=o.getClass().getField(name);f.setAccessible(true);f.set(o,v);}
    private static JSONArray point(Point p){return p==null?null:new JSONArray().put(p.x).put(p.y);}
    private static JSONArray points(List<int[]> p){JSONArray a=new JSONArray();for(int[] v:p)a.put(new JSONArray().put(v[0]).put(v[1]).put(v[2]));return a;}
    private static JSONArray strings(List<String> p){JSONArray a=new JSONArray();for(String v:p)a.put(v);return a;}

    private static final class Execution {
        JSONObject output;Throwable failure;long elapsed;
    }
    private static Execution run(Runnable motion,int count)throws Exception {
        NativeAuth.reset(count);Execution e=new Execution();
        Thread worker=new Thread(()->{
            long began=System.nanoTime();
            try{motion.run();}catch(Throwable t){
                if(!(t instanceof InterruptedException)&&!(t.getCause() instanceof InterruptedException))e.failure=t;
            }finally{e.elapsed=(System.nanoTime()-began)/1000000;}
        },"reference-calculation");
        worker.setDaemon(true);worker.start();worker.join(6000);
        if(worker.isAlive()){worker.interrupt();worker.join(1000);throw new IllegalStateException("Original calculation exceeded bounded capture");}
        if(e.failure!=null)throw new IllegalStateException("Calculation failed",e.failure);
        if(NativeAuth.moves.size()!=count)throw new IllegalStateException("Capture incomplete: "+NativeAuth.moves.size()+"/"+count);
        e.output=new JSONObject().put("moves",points(NativeAuth.moves)).put("elapsedMs",e.elapsed);
        return e;
    }
    private static JSONObject reference(int mode,boolean twoPhase,int direction,int cx,int cy,int sx,int sy,float radius,long targetSeed,long pathSeed,int count)throws Exception {
        Object gen=originalGenerator.getConstructor(int.class,boolean.class).newInstance(mode,twoPhase);
        RecordingRandom targets=new RecordingRandom(targetSeed);
        set(gen,"a",targets);
        Runnable motion=(Runnable)originalGenerator.getMethod("a",Point.class,Point.class,float.class,int.class,int.class)
            .invoke(gen,new Point(sx,sy),new Point(cx,cy),radius,direction,30);
        RecordingRandom path=new RecordingRandom(pathSeed);
        set(motion,"d",path);set(motion,"e",DURATION);set(motion,"f",STEPS);
        JSONObject result=new JSONObject().put("target",point((Point)field(motion,"b")))
            .put("settleTarget",point((Point)field(motion,"h")))
            .put("spreadBits",Integer.toHexString(Float.floatToIntBits((Float)field(motion,"g"))))
            .put("settleSpreadBits",Integer.toHexString(Float.floatToIntBits((Float)field(motion,"i"))))
            .put("strong",field(motion,"j")).put("targetCalls",strings(targets.calls));
        JSONObject exec=run(motion,count).output;
        result.put("moves",exec.get("moves")).put("elapsedMs",exec.get("elapsedMs")).put("pathCalls",strings(path.calls));
        return result;
    }
    private static JSONObject candidate(int mode,boolean twoPhase,int direction,int cx,int cy,int sx,int sy,float radius,long targetSeed,long pathSeed,int count)throws Exception {
        Class<?> cls=Class.forName("cn.offline.mapper.engine.JoystickTrajectory");
        Class<?> params=Class.forName(cls.getName()+"$Params"),sink=Class.forName(cls.getName()+"$MoveSink"),sleeper=Class.forName(cls.getName()+"$Sleeper");
        Object config=cls.getMethod("preset",int.class,boolean.class).invoke(null,mode,twoPhase);
        Object move=Proxy.newProxyInstance(cls.getClassLoader(),new Class[]{sink},(p,m,a)->{
            if(m.getName().equals("move")){NativeAuth.E0((Integer)a[0],(Integer)a[1],(Integer)a[2]);return null;}
            return null;
        });
        Object sleep=Proxy.newProxyInstance(cls.getClassLoader(),new Class[]{sleeper},(p,m,a)->{Thread.sleep((Long)a[0]);return null;});
        RecordingRandom targets=new RecordingRandom(targetSeed),path=new RecordingRandom(pathSeed);
        Object motion=cls.getMethod("random",Point.class,Point.class,float.class,int.class,int.class,int.class,int.class,params,Random.class,Random.class,sink,sleeper)
            .invoke(null,new Point(sx,sy),new Point(cx,cy),radius,direction,30,g.d.width,g.d.height,config,targets,path,move,sleep);
        set(motion,"durationMs",DURATION);set(motion,"pointCount",STEPS);path.setSeed(pathSeed);path.calls.clear();
        JSONObject result=new JSONObject().put("target",point((Point)field(motion,"target")))
            .put("settleTarget",point((Point)field(motion,"settleTarget")))
            .put("spreadBits",Integer.toHexString(Float.floatToIntBits((Float)field(motion,"spread"))))
            .put("settleSpreadBits",Integer.toHexString(Float.floatToIntBits((Float)field(motion,"settleSpread"))))
            .put("strong",field(motion,"strong")).put("targetCalls",strings(targets.calls));
        JSONObject exec=run((Runnable)motion,count).output;
        result.put("moves",exec.get("moves")).put("elapsedMs",exec.get("elapsedMs")).put("pathCalls",strings(path.calls));
        return result;
    }
    private static JSONArray timingCases(boolean compare)throws Exception {
        Method ref=originalMotion.getMethod("b",int.class,int.class);
        Method cand=compare?Class.forName("cn.offline.mapper.engine.JoystickTrajectory$Motion").getMethod("splitDurations",int.class,int.class):null;
        JSONArray result=new JSONArray();
        for(int total:new int[]{33,37,45,46,80})for(int count:new int[]{8,15,22,30}){
            int[] a=(int[])ref.invoke(null,total,count);JSONArray arr=new JSONArray();for(int x:a)arr.put(x);
            JSONObject row=new JSONObject().put("total",total).put("count",count).put("reference",arr);
            if(compare){int[] b=(int[])cand.invoke(null,total,count);row.put("equal",Arrays.equals(a,b));if(!Arrays.equals(a,b))throw new AssertionError("Duration division differs "+row);}
            result.put(row);
        }
        return result;
    }
    private static JSONArray directCases(boolean compare)throws Exception {
        Class<?> ref=Class.forName("r3.a",true,original);Object gen=ref.getConstructor().newInstance();
        JSONArray result=new JSONArray();
        for(int dir=1;dir<=8;dir++){
            Point a=new Point(700,500);Runnable r=(Runnable)ref.getMethod("a",Point.class,Point.class,float.class,int.class,int.class).invoke(gen,a,new Point(700,500),90.5f,dir,30);
            JSONObject reference=run(r,1).output;
            JSONObject row=new JSONObject().put("direction",dir).put("radius",90.5).put("reference",reference);
            if(compare){
                Class<?> cls=Class.forName("cn.offline.mapper.engine.JoystickTrajectory"),sink=Class.forName(cls.getName()+"$MoveSink");
                Object move=Proxy.newProxyInstance(cls.getClassLoader(),new Class[]{sink},(p,m,v)->{NativeAuth.E0((Integer)v[0],(Integer)v[1],(Integer)v[2]);return null;});
                Point b=new Point(700,500);Runnable q=(Runnable)cls.getMethod("direct",Point.class,Point.class,float.class,int.class,int.class,sink).invoke(null,b,new Point(700,500),90.5f,dir,30,move);
                JSONObject candidate=run(q,1).output;boolean equal=reference.get("moves").toString().equals(candidate.get("moves").toString())&&a.equals(b);
                row.put("candidate",candidate).put("equal",equal);if(!equal)throw new AssertionError("Direct calculation differs "+row);
            }
            result.put(row);
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        if(args.length<1)throw new IllegalArgumentException("Exact recovered DEX path required");
        original=new DexClassLoader(args[0],"/data/local/tmp/mapper-trajectory-oracle",null,TrajectoryOracle.class.getClassLoader());
        originalGenerator=Class.forName("r3.d",true,original);originalMotion=Class.forName("r3.e",true,original);
        if(originalGenerator.getClassLoader()!=original||originalMotion.getClassLoader()!=original)throw new IllegalStateException("Reference class is not from original DEX");
        boolean compare=args.length>1&&args[1].equals("compare");
        JSONObject report=new JSONObject().put("schema",1).put("mode",compare?"original-dex-vs-candidate":"original-dex-capture")
            .put("originalDex",args[0]).put("referenceImplementation","unmodified recovered DEX executed by ART")
            .put("originalAPKStarted",false).put("nativeLibraryLoaded",false).put("originalAuthenticationCalled",false)
            .put("durationOverride",DURATION).put("pointCountOverride",STEPS)
            .put("wallClockTimingCompared",false).put("timingAllocator",timingCases(compare));
        JSONArray tests=new JSONArray();int mismatch=0;
        for(int caseNo=0;caseNo<25;caseNo++){
            int mode=caseNo<24?caseNo/8:1,dir=caseNo<24?caseNo%8+1:1;
            boolean twoPhase=dir%3==0;boolean edge=dir==8,near=dir==7;
            int cx=edge?40:700,cy=edge?40:500,sx=near?cx+80:cx,sy=cy;
            float radius=90;long ts=mode*101L+dir*7001L,ps=caseNo==24?6:dir*43L+mode*29L;
            int count=twoPhase?STEPS+14+2:STEPS+2;
            JSONObject row=new JSONObject().put("mode",mode).put("twoPhase",twoPhase).put("direction",dir)
                .put("center",point(new Point(cx,cy))).put("start",point(new Point(sx,sy))).put("radius",radius)
                .put("targetSeed",ts).put("pathSeed",ps).put("captureMoves",count);
            JSONObject a=reference(mode,twoPhase,dir,cx,cy,sx,sy,radius,ts,ps,count);row.put("reference",a);
            if(compare){
                JSONObject b=candidate(mode,twoPhase,dir,cx,cy,sx,sy,radius,ts,ps,count);row.put("candidate",b);
                JSONArray differences=new JSONArray();
                for(String key:new String[]{"target","settleTarget","spreadBits","settleSpreadBits","strong","targetCalls","moves","pathCalls"})
                    if(!Objects.equals(a.opt(key)==null?null:a.opt(key).toString(),b.opt(key)==null?null:b.opt(key).toString()))differences.put(key);
                row.put("differences",differences).put("equal",differences.length()==0);if(differences.length()>0)mismatch++;
            }
            tests.put(row);
        }
        report.put("cases",tests).put("directCases",directCases(compare)).put("mismatches",mismatch).put("passed",compare&&mismatch==0)
            .put("limitations",new JSONArray().put("Only calculation generators r3.d/r3.e and duration allocation; account safety is not evaluated.")
                .put("Test adapters only record output and provide display dimensions; initial native touch dispatch is outside these tests.")
                .put("Real scheduler sleep duration is recorded diagnostically and is not compared for exact timing."));
        System.out.println(report);
        if(compare&&mismatch!=0)System.exit(1);
    }
}
