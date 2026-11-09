package cn.offline.mapper.engine;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Launch through su: CLASSPATH=<apk> app_process / <this class> socket uid library token. */
public final class RootDaemon {
    private final Object stateLock=new Object(), outputLock=new Object();
    private volatile boolean alive=true;
    private volatile long inputEpoch;
    private boolean configured;
    private MappingEngine engine;
    private TouchInjector injector;
    private MouseCursor cursor;
    private long nextCursor;
    private int cursorIntervalMs=8;
    private boolean cursorDirty;
    private BufferedWriter output;
    private volatile LocalSocket client;
    private LocalServerSocket server;
    private long eventCount;
    private int lastCode=-1;
    private String lastError="";
    private final HashMap<Integer,Long> systemKeyDown=new HashMap<>();
    private final HashMap<Integer,Integer> systemKeyRepeats=new HashMap<>();

    public static void main(String[] args) {
        startup("进入引擎 uid="+Process.myUid()+" SDK="+android.os.Build.VERSION.SDK_INT);
        if(args.length!=4){System.err.println("Usage: RootDaemon socket allowedUid nativeLibrary token");return;}
        if(Process.myUid()!=0){System.err.println("RootDaemon requires uid 0");return;}
        RootDaemon daemon=new RootDaemon();
        try {
            int allowedUid=Integer.parseInt(args[1]);
            if(allowedUid<10000 || args[0].length()<8 || args[3].length()<24)
                throw new IllegalArgumentException("Invalid daemon credentials");
            startup("正在加载输入库");
            System.load(args[2]);
            startup("输入库已加载");
            Runtime.getRuntime().addShutdownHook(new Thread(daemon::shutdown,"mapper-cleanup"));
            daemon.serve(args[0],allowedUid,args[3]);
        } catch(Throwable e){System.err.println("RootDaemon: "+e);e.printStackTrace(System.err);System.err.flush();}
        finally {daemon.shutdown();}
    }
    private static void startup(String stage){System.err.println("MapperStartup: "+stage);System.err.flush();}

    private void serve(String socketName,int allowedUid,String token) throws Exception {
        startup("正在连接系统输入接口");
        injector=new TouchInjector();
        startup("系统输入接口已连接");
        engine=new MappingEngine(injector);cursor=new MouseCursor(injector);
        boolean reverse=socketName.startsWith("reverse:");
        if(!reverse){
            startup("正在建立本地服务端点");
            server=new LocalServerSocket(socketName);
            startup("本地服务端点已建立，等待应用连接");
        }
        final long deadline=SystemClock.uptimeMillis()+20000;
        Thread watchdog=new Thread(()->{
            while(alive && client==null && SystemClock.uptimeMillis()<deadline) {
                try{Thread.sleep(100);}catch(InterruptedException ignored){return;}
            }
            if(alive && client==null)shutdown();
        },"mapper-connect-timeout");watchdog.setDaemon(true);watchdog.start();
        if(reverse) {
            startup("正在回连应用端点");
            LocalSocket candidate=new LocalSocket();
            try {
                candidate.connect(new LocalSocketAddress(socketName.substring("reverse:".length()),LocalSocketAddress.Namespace.ABSTRACT));
                if(!serveClient(candidate,allowedUid,token))throw new SecurityException("应用回连接口未通过验证");
            } finally {try{candidate.close();}catch(Exception ignored){}}
            return;
        }
        while(alive) {
            LocalSocket candidate=server.accept();
            startup("收到应用连接");
            if(serveClient(candidate,allowedUid,token))return;
        }
    }
    private boolean serveClient(LocalSocket candidate,int allowedUid,String token) throws Exception {
            Credentials peer=candidate.getPeerCredentials();
            if(peer.getUid()!=allowedUid && (server==null || peer.getUid()!=0)){System.err.println("Denied peer uid "+peer.getUid());candidate.close();return false;}
            candidate.setSoTimeout(5000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(candidate.getInputStream(),StandardCharsets.UTF_8));
            try {
                String line=readLine(reader);if(line==null){candidate.close();return false;}
                JSONObject auth=new JSONObject(line);
                if(!"auth".equals(auth.optString("cmd")) || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),auth.optString("token").getBytes(StandardCharsets.UTF_8))) {
                    System.err.println("Denied authentication");candidate.close();return false;
                }
            } catch(Exception e){System.err.println("Authentication failed: "+message(e));candidate.close();return false;}
            candidate.setSoTimeout(0);client=candidate;
            startup("本地认证通过");
            output=new BufferedWriter(new OutputStreamWriter(client.getOutputStream(),StandardCharsets.UTF_8));
            send(new JSONObject().put("type","ready").put("uid",Process.myUid())
                    .put("devices",new JSONArray(NativeInput.devices())).put("inputDiagnostics",new JSONObject(NativeInput.diagnostics())).put("injection",true));
            Thread worker=new Thread(this::inputLoop,"mapper-input");worker.setDaemon(true);worker.start();
            try {
                String line;
                while(alive && (line=readLine(reader))!=null) {
                    JSONObject request=null;
                    try{request=new JSONObject(line);command(request);}
                    catch(Exception e){error(message(e),request==null?"parse":request.optString("cmd","runtime"));}
                }
            } finally {shutdown();worker.join(1000);}
            return true;
    }
    private static String readLine(BufferedReader reader) throws Exception {
        StringBuilder line=new StringBuilder();int c;
        while((c=reader.read())!=-1) {
            if(c=='\n')return line.toString();
            if(c!='\r')line.append((char)c);
            if(line.length()>131072)throw new IllegalArgumentException("命令过长");
        }
        return line.length()==0?null:line.toString();
    }
    private void command(JSONObject value) throws Exception {
        synchronized(stateLock) {
            String cmd=value.getString("cmd");
            switch(cmd) {
                case "config":
                    configured=false;
                    pause();
                    engine.configure(value.getJSONObject("profile"),value.getInt("width"),value.getInt("height"));
                    cursor.configure(value.getInt("width"),value.getInt("height"));
                    int cursorHz=Math.max(30,Math.min(240,value.optInt("cursorHz",120)));
                    cursorIntervalMs=Math.max(1,1000/cursorHz);nextCursor=0;cursorDirty=true;
                    configured=true;lastError="";break;
                case "start":
                    if(!configured)throw new IllegalStateException("请先加载映射配置");
                    if(!engine.running()) {
                        inputEpoch++;
                        if(!NativeInput.setGrab(true))throw new IllegalStateException(NativeInput.error());
                        engine.start();lastError="";
                    }
                    break;
                case "pause":pause();break;
                case "cancel":inputEpoch++;engine.cancel();cursor.cancel();break;
                case "status":case "ping":break;
                case "stop":shutdown();return;
                default:throw new IllegalArgumentException("未知命令: "+cmd);
            }
            status();
        }
    }
    private void pause() throws Exception {
        inputEpoch++;
        try{engine.pause();cursor.cancel();releaseSystemKeys();}finally{NativeInput.setGrab(false);}
    }
    private void inputLoop() {
        long nextStatus=0;
        while(alive) {
            try {
                // NativeInput only holds its own lock briefly; never call it while holding outputLock.
                long readEpoch=inputEpoch;
                int[] events=NativeInput.poll(8);
                synchronized(stateLock) {
                    if(!alive)break;
                    // A pause/reconfiguration may complete while poll is waiting.
                    // Never replay its previous session's events into the new layout.
                    if(readEpoch!=inputEpoch)continue;
                    MouseMotionBatch motion=new MouseMotionBatch(this::mouse);
                    for(int i=0;i+3<events.length;i+=4) {
                        int device=events[i],type=events[i+1],code=events[i+2],value=events[i+3];
                        long now=SystemClock.uptimeMillis();
                        if(type==-1){motion.clear();String reason=NativeInput.error();pause();lastError=reason;error(lastError);break;}
                        if(!engine.running())continue;
                        if(type==1) {
                            motion.flush(now);
                            eventCount++;lastCode=code;
                            if(code==114||code==115||code==116)passSystemKey(code,value);
                            else if(code==272 && (!engine.hasBinding(code)||engine.freePointerButtons())) {
                                if(!engine.aimLocked())cursor.key(code,value);
                            } else {
                                boolean wasLocked=engine.aimLocked();engine.key(device,code,value,now);
                                if(wasLocked!=engine.aimLocked()){cursor.cancel();status();}
                            }
                            if(!engine.running()){pause();status();break;}
                        } else if(type==2) {
                            if(code==0||code==1)motion.relative(device,code,value,now);
                            else if(code==8) {
                                motion.flush(now);
                                if(engine.hasBinding(value>0?768:769))engine.wheel(device,value,now);
                            }
                            eventCount++;
                        }
                    }
                    long now=SystemClock.uptimeMillis();
                    motion.flush(now);engine.tick(now);flushCursor(now);
                    if(SystemClock.uptimeMillis()>=nextStatus){status();nextStatus=SystemClock.uptimeMillis()+1000;}
                }
            } catch(Exception e) {
                if(!alive)break;
                synchronized(stateLock) {
                    try{pause();}catch(Exception ignored){}
                    try{error(message(e));}catch(Exception writeFailure){shutdown();}
                }
            }
        }
    }
    private void passSystemKey(int evdevCode,int value) throws Exception {
        int code=evdevCode==114?KeyEvent.KEYCODE_VOLUME_DOWN:evdevCode==115?KeyEvent.KEYCODE_VOLUME_UP:KeyEvent.KEYCODE_POWER;
        long now=SystemClock.uptimeMillis();
        if(value!=0 && !systemKeyDown.containsKey(evdevCode))systemKeyDown.put(evdevCode,now);
        long down=systemKeyDown.containsKey(evdevCode)?systemKeyDown.get(evdevCode):now;
        int repeat=value==2?systemKeyRepeats.getOrDefault(evdevCode,0)+1:0;
        systemKeyRepeats.put(evdevCode,repeat);
        KeyEvent event=new KeyEvent(down,now,value==0?KeyEvent.ACTION_UP:KeyEvent.ACTION_DOWN,code,repeat,0,-1,evdevCode,0,InputDevice.SOURCE_KEYBOARD);
        injector.injectEvent(event);
        if(value==0){systemKeyDown.remove(evdevCode);systemKeyRepeats.remove(evdevCode);}
    }
    private void releaseSystemKeys() throws Exception {
        for(Integer code:systemKeyDown.keySet().toArray(new Integer[0]))passSystemKey(code,0);
    }
    private void mouse(float dx,float dy,long now) throws Exception {
        if(!engine.running())return;
        if(engine.aimLocked())engine.mouse(dx,dy,now);
        else {
            cursor.move(dx,dy);
            if(dx!=0||dy!=0)cursorDirty=true;
        }
    }
    private void flushCursor(long now) throws Exception {
        if(!engine.running()||engine.aimLocked()||!cursorDirty||now<nextCursor)return;
        nextCursor=now+cursorIntervalMs;cursorDirty=false;
        send(new JSONObject().put("type","cursor").put("x",cursor.x()).put("y",cursor.y()));
    }
    private void status() throws Exception {
        send(new JSONObject().put("type","status").put("running",engine.running()).put("aimLocked",engine.aimLocked())
                .put("devices",new JSONArray(NativeInput.devices())).put("eventCount",eventCount).put("lastCode",lastCode).put("error",lastError)
                .put("inputDiagnostics",new JSONObject(NativeInput.diagnostics()))
                .put("cursorX",cursor.x()).put("cursorY",cursor.y()));
    }
    private void error(String message) throws Exception {
        error(message,"runtime");
    }
    private void error(String message,String operation) throws Exception {
        lastError=message;send(new JSONObject().put("type","error").put("message",message).put("operation",operation)
                .put("devices",new JSONArray(NativeInput.devices())).put("inputDiagnostics",new JSONObject(NativeInput.diagnostics())));
    }
    private void send(JSONObject value) throws Exception {
        synchronized(outputLock){if(output!=null){output.write(value.toString());output.newLine();output.flush();}}
    }
    private static String message(Throwable e) {
        Throwable root=e;while(root.getCause()!=null)root=root.getCause();
        return root.getMessage()==null?root.toString():root.getMessage();
    }
    private void shutdown() {
        alive=false;
        // Unblock a stalled socket write before waiting for the mapping state lock.
        try{if(client!=null)client.close();}catch(Exception ignored){}
        try{if(server!=null)server.close();}catch(Exception ignored){}
        synchronized(stateLock) {
            try{if(engine!=null)engine.pause();if(cursor!=null)cursor.cancel();if(injector!=null)releaseSystemKeys();}catch(Throwable ignored){}
            try{NativeInput.close();}catch(Throwable ignored){}
        }
    }
}
