package cn.offline.mapper;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class RootBridge {
    public interface Listener {void onChanged();}
    private static RootBridge instance;
    public static synchronized RootBridge get(Context c){if(instance==null)instance=new RootBridge(c.getApplicationContext());return instance;}
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private volatile boolean connected,running,activating,aimLocked,cursorVisible;
    private volatile float cursorX,cursorY;
    private volatile String state="尚未激活 Root 服务";
    private volatile JSONObject detail=new JSONObject();
    private LocalSocket socket;
    private BufferedWriter writer;
    private java.lang.Process child;
    private volatile int generation;
    private final StringBuilder logs=new StringBuilder();
    private RootBridge(Context c){context=c;}
    public boolean connected(){return connected;}
    public boolean running(){return running;}
    public boolean activating(){return activating;}
    public boolean aimLocked(){return aimLocked;}
    public float cursorX(){return cursorX;}
    public float cursorY(){return cursorY;}
    public boolean cursorVisible(){return running&&!aimLocked&&cursorVisible;}
    public String status(){return state;}
    public JSONObject detail(){return detail;}
    public synchronized String diagnostics(){return state+"\n"+detail+"\n"+logs;}
    public void addListener(Listener l){listeners.add(l);}
    public void removeListener(Listener l){listeners.remove(l);}
    private void changed(){main.post(()->{for(Listener l:listeners)l.onChanged();});}
    private synchronized void log(String s){logs.append(s).append('\n');if(logs.length()>12000)logs.delete(0,logs.length()-12000);Log.i("OfflineMapper",s);}
    private void fail(String s){state=s;running=false;changed();log(s);}
    public synchronized void activate(){
        if(connected||activating)return;activating=true;state="正在请求 Root，请在权限管理器允许…";changed();
        final int ticket=++generation;
        work.execute(()->launch(ticket));
    }
    private void launch(int ticket){
        try{
            String name="offline.mapper."+UUID.randomUUID();String token=UUID.randomUUID().toString();
            String apk=context.getApplicationInfo().sourceDir;
            String lib=context.getApplicationInfo().nativeLibraryDir+"/libmapper_input.so";
            if(!new File(lib).isFile())throw new IOException("缺少当前架构的输入引擎: "+lib);
            String command="CLASSPATH="+quote(apk)+" /system/bin/app_process /system/bin cn.offline.mapper.engine.RootDaemon "+quote(name)+" "+Process.myUid()+" "+quote(lib)+" "+quote(token);
            java.lang.Process process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            synchronized(this){if(ticket!=generation){process.destroy();return;}child=process;}
            Thread drain=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)log(line);}catch(IOException ignored){}},"root-log");drain.setDaemon(true);drain.start();
            long deadline=System.currentTimeMillis()+45000;LocalSocket candidate=null;
            while(ticket==generation&&System.currentTimeMillis()<deadline){
                if(!process.isAlive())throw new IOException("Root服务启动失败（请查看诊断日志和SU授权）");
                try{candidate=new LocalSocket();candidate.connect(new LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT));break;}catch(IOException e){if(candidate!=null)candidate.close();candidate=null;Thread.sleep(250);}
            }
            if(candidate==null)throw new IOException("Root授权或服务启动超时，请允许SU后重试");
            synchronized(this){if(ticket!=generation){candidate.close();return;}socket=candidate;writer=new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream(),StandardCharsets.UTF_8));}
            candidate.setSoTimeout(10000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(candidate.getInputStream(),StandardCharsets.UTF_8));
            writeNow(new JSONObject().put("cmd","auth").put("token",token));
            String first=reader.readLine();if(first==null)throw new IOException("Root服务没有返回握手结果");
            JSONObject hello=new JSONObject(first);if(!"ready".equals(hello.optString("type"))||hello.optInt("uid",-1)!=0||!hello.optBoolean("injection"))throw new IOException("Root引擎未就绪: "+hello.optString("message",first));
            synchronized(this){if(ticket!=generation){candidate.close();return;}candidate.setSoTimeout(0);connected=true;activating=false;detail=hello;state="Root 已激活 · 映射未启动";}changed();
            final LocalSocket live=candidate;
            Thread pump=new Thread(()->readMessages(reader,live,ticket),"root-events");pump.setDaemon(true);pump.start();
        }catch(Exception e){synchronized(this){if(ticket==generation){cleanup();activating=false;fail(e.getMessage()==null?e.toString():e.getMessage());}}}
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private void readMessages(BufferedReader reader,LocalSocket live,int ticket){
        try{String line;while(ticket==generation&&(line=reader.readLine())!=null){
            if(line.length()>65536)throw new IOException("服务消息过长");JSONObject m=new JSONObject(line);String type=m.optString("type");
            synchronized(this){if(ticket!=generation)return;
            if(type.equals("cursor")){cursorX=(float)m.optDouble("x");cursorY=(float)m.optDouble("y");cursorVisible=m.optBoolean("visible",true);changed();}
            else if(type.equals("status")||type.equals("ready")){detail=m;cursorX=(float)m.optDouble("cursorX",cursorX);cursorY=(float)m.optDouble("cursorY",cursorY);cursorVisible=m.optBoolean("running",false)&&!m.optBoolean("aimLocked",false);running=m.optBoolean("running",running);aimLocked=m.optBoolean("aimLocked",false);String error=m.optString("error","");state=error.isEmpty()?(running?(aimLocked?"映射运行中 · 视角锁定":"映射运行中 · 自由鼠标模式"):"Root 已激活 · 映射已暂停"):"服务错误："+error;changed();}
            else if(type.equals("error")){fail("服务错误："+m.optString("message",m.toString()));}
            }
        }}catch(Exception e){if(ticket==generation)log("服务连接结束："+e);}
        finally{synchronized(this){if(ticket==generation){cleanup();connected=false;running=false;activating=false;state="Root 服务已断开，键鼠已释放";changed();}}}
    }
    private synchronized void writeNow(JSONObject msg)throws IOException{if(writer==null)throw new IOException("尚未连接 Root 服务");writer.write(msg.toString());writer.newLine();writer.flush();}
    public void send(JSONObject msg){final int ticket=generation;work.execute(()->{synchronized(this){if(ticket!=generation)return;try{writeNow(msg);}catch(IOException e){fail("发送失败："+e.getMessage());}}});}
    public void configure(JSONObject p,int width,int height){ProfileStore.validate(p);try{send(new JSONObject().put("cmd","config").put("profile",p).put("width",width).put("height",height));}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private void command(String cmd){try{send(new JSONObject().put("cmd",cmd));}catch(JSONException ignored){}}
    public void startMapping(){if(!connected){fail("请先激活 Root 服务");return;}command("start");}
    public void pauseMapping(){if(connected)command("pause");}
    public void refresh(){if(connected)command("status");}
    public synchronized void stop(){
        generation++;cleanup();connected=false;running=false;activating=false;cursorVisible=false;
        state="服务已停止 · 键鼠已释放";changed();
    }
    private synchronized void cleanup(){
        try{if(socket!=null)socket.close();}catch(IOException ignored){}socket=null;writer=null;
        if(child!=null){child.destroy();child=null;}connected=false;running=false;
    }
}
