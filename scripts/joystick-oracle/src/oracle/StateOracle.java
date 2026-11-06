package oracle;

import android.graphics.Point;
import dalvik.system.DexClassLoader;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import moe.heroman.eventtest.auth.NativeAuth;
import org.json.*;

/** Original controller and adapter bytecode versus independent controller in ordinary mode. */
public final class StateOracle {
    private static ClassLoader original;
    private static Class<?> refClass,adapterClass,radialClass,candidateClass,sinkClass;
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    private static void await(Object o,boolean ref)throws Exception{
        Future<?> f=(Future<?>)field(o,ref?"l":"motion");
        if(f!=null&&!f.isCancelled())f.get(3,TimeUnit.SECONDS);
    }
    private static Object create(boolean ref,int radiusMode)throws Exception{
        if(ref)return refClass.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class,int.class,boolean.class,int.class,boolean.class)
            .newInstance(960,540,180,180,radiusMode,90,90,true,0,false);
        final int[] last={960,540};final HashSet<Integer> active=new HashSet<>();
        Object sink=Proxy.newProxyInstance(candidateClass.getClassLoader(),new Class[]{sinkClass},(p,m,a)->{
            String name=m.getName();
            if(name.equals("contains"))return active.contains((Integer)a[0]);
            if(name.equals("down")){active.add((Integer)a[0]);last[0]=(int)(float)(Float)a[1];last[1]=(int)(float)(Float)a[2];NativeAuth.state("D",(Integer)a[0],last[0],last[1]);}
            else if(name.equals("move")){last[0]=(int)(float)(Float)a[1];last[1]=(int)(float)(Float)a[2];NativeAuth.state("M",(Integer)a[0],last[0],last[1]);}
            else if(name.equals("up")){NativeAuth.state("U",(Integer)a[0],last[0],last[1]);active.remove((Integer)a[0]);}
            else if(name.equals("cancel")){active.clear();}
            return null;
        });
        JSONObject j=new JSONObject().put("x",.5).put("y",.5).put("radius",90.0/1080).put("radiusPixels",90)
            .put("trajectoryMode",2).put("radiusSwitchMode",radiusMode).put("radiusSwitchPercent",50).put("radiusSwitchCode",58);
        Constructor<?> ctor=candidateClass.getDeclaredConstructor(sinkClass,JSONObject.class,String.class,int.class,int.class);ctor.setAccessible(true);
        return ctor.newInstance(sink,j,"",1920,1080);
    }
    private static JSONArray events(){JSONArray a=new JSONArray();synchronized(NativeAuth.class){for(String x:NativeAuth.stateEvents)a.put(x);}return a;}
    private static JSONArray mask(boolean ref,int mask)throws Exception{
        NativeAuth.resetState();Object controller=create(ref,0);
        try{
            boolean[] values=new boolean[]{(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0};
            if(ref){boolean[] flags=(boolean[])field(controller,"j");flags[0]=values[0];flags[1]=values[1];flags[2]=values[3];flags[3]=values[2];call(controller,"d",new Class[]{});}
            else call(controller,"direction",new Class[]{boolean.class,boolean.class,boolean.class,boolean.class},values[0],values[1],values[2],values[3]);
            await(controller,ref);
        }finally{call(controller,ref?"g":"close",new Class[]{});}
        return events();
    }
    private static JSONArray sequence(boolean ref,int radiusMode,String[] commands)throws Exception{
        NativeAuth.resetState();Object controller=create(ref,radiusMode);Object[] adapters=new Object[4];Object radiusAdapter=null;
        if(ref){for(int i=0;i<4;i++)adapters[i]=adapterClass.getConstructor(refClass,int.class).newInstance(controller,new int[]{1,2,4,3}[i]);radiusAdapter=radialClass.getConstructor(refClass).newInstance(controller);}
        JSONArray timeline=new JSONArray();boolean[] directions=new boolean[4];
        try{
            for(String command:commands){
                String[] bits=command.split(":");boolean pressed=bits[1].equals("1");
                if(bits[0].equals("R")){
                    if(ref)call(radiusAdapter,pressed?"c":"d",new Class[]{int.class},0);
                    else call(controller,"radiusKey",new Class[]{boolean.class},pressed);
                }else{
                    int index=Integer.parseInt(bits[0]);directions[index]=pressed;
                    if(ref)call(adapters[index],pressed?"c":"d",new Class[]{int.class},0);
                    else call(controller,"direction",new Class[]{boolean.class,boolean.class,boolean.class,boolean.class},directions[0],directions[1],directions[2],directions[3]);
                }
                await(controller,ref);timeline.put(new JSONObject().put("command",command).put("events",events()));
            }
        }finally{call(controller,ref?"g":"close",new Class[]{});}
        timeline.put(new JSONObject().put("command","close").put("events",events()));return timeline;
    }
    public static void main(String[] args)throws Exception{
        original=new DexClassLoader(args[0],"/data/local/tmp/mapper-trajectory-oracle",null,StateOracle.class.getClassLoader());
        refClass=Class.forName("r3.c",true,original);adapterClass=Class.forName("b4.j1",true,original);radialClass=Class.forName("j3.h",true,original);
        candidateClass=Class.forName("cn.offline.mapper.engine.JoystickController");sinkClass=Class.forName("cn.offline.mapper.engine.TouchSink");
        if(refClass.getClassLoader()!=original||adapterClass.getClassLoader()!=original||radialClass.getClassLoader()!=original)throw new IllegalStateException("Original bytecode identity differs");
        JSONArray masks=new JSONArray();int failures=0;
        for(int i=0;i<16;i++){JSONArray a=mask(true,i),b=mask(false,i);boolean equal=a.toString().equals(b.toString());if(!equal)failures++;masks.put(new JSONObject().put("mask",i).put("reference",a).put("candidate",b).put("equal",equal));}
        JSONArray sequences=new JSONArray();String[][] commands={
            {"0:1","0:1","3:1","2:1","0:0","1:1","3:0","2:0","1:0"},
            {"R:1","0:1","R:1","R:0","R:0","3:1","R:1","R:0","0:0","3:0"},
            {"R:1","R:0","0:1","R:1","R:0","R:1","R:1","3:1","R:0","0:0","3:0"}
        };
        for(int mode=0;mode<3;mode++){JSONArray a=sequence(true,mode,commands[mode]),b=sequence(false,mode,commands[mode]);boolean equal=a.toString().equals(b.toString());if(!equal)failures++;sequences.put(new JSONObject().put("radiusMode",mode).put("reference",a).put("candidate",b).put("equal",equal));}
        JSONObject report=new JSONObject().put("passed",failures==0).put("mismatches",failures).put("maskCases",masks).put("sequenceCases",sequences)
            .put("referenceImplementation","unmodified original r3.c / b4.j1 / j3.h bytecode executed by ART")
            .put("ordinaryModeOnly",true).put("originalNativeCodeLoaded",false).put("wallClockTimingCompared",false)
            .put("limits",new JSONArray().put("Callbacks compared before the operating-system MotionEvent injector.").put("Concurrent key arrival races, native touch dispatch and account safety are not evaluated."));
        System.out.println(report);if(failures>0)System.exit(1);
    }
}
