package cn.offline.mapper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.InputDevice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local device metadata only. Never records input events, keys or typed text. */
public final class InputDiagnosticsStore {
    private final SharedPreferences prefs;
    private String previous="";
    public InputDiagnosticsStore(Context context){prefs=context.getSharedPreferences("input-diagnostics-v1",Context.MODE_PRIVATE);}

    public static JSONArray systemDevices(){
        JSONArray result=new JSONArray();
        for(int id:InputDevice.getDeviceIds())try{
            InputDevice d=InputDevice.getDevice(id);
            if(d==null||d.isVirtual())continue;
            boolean external=Build.VERSION.SDK_INT>=29?d.isExternal():(d.getVendorId()!=0||d.getProductId()!=0);
            boolean keyboard=(d.getSources()&InputDevice.SOURCE_KEYBOARD)==InputDevice.SOURCE_KEYBOARD;
            boolean mouse=(d.getSources()&InputDevice.SOURCE_MOUSE)==InputDevice.SOURCE_MOUSE;
            if(external&&(keyboard||mouse))result.put(new JSONObject().put("name",d.getName()).put("vendor",d.getVendorId())
                    .put("product",d.getProductId()).put("keyboard",keyboard).put("mouse",mouse));
        }catch(Exception ignored){}
        return result;
    }
    public synchronized void update(JSONObject message){
        JSONObject scan=message.optJSONObject("inputDiagnostics");if(scan==null)return;
        String value=scan.toString();if(value.equals(previous))return;previous=value;
        prefs.edit().putString("currentScan",value).apply();
    }
    public void captureFailure(JSONObject message){captureFailure(message,systemDevices());}
    public synchronized void captureFailure(JSONObject message,JSONArray systemDevices){
        try{
            JSONObject snapshot=new JSONObject().put("time",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date()))
                    .put("message",message.optString("message",message.optString("error","")))
                    .put("operation",message.optString("operation","runtime"))
                    .put("systemDevices",new JSONArray(systemDevices.toString()));
            JSONObject scan=message.optJSONObject("inputDiagnostics");if(scan!=null)snapshot.put("inputDiagnostics",new JSONObject(scan.toString()));
            JSONArray devices=message.optJSONArray("devices");if(devices!=null)snapshot.put("devices",new JSONArray(devices.toString()));
            // Finish storing the failed-attempt evidence before the user unplugs the hub.
            prefs.edit().putString("lastFailure",snapshot.toString()).commit();
        }catch(Exception ignored){}
    }
    public synchronized JSONObject lastFailure(){
        try{return new JSONObject(prefs.getString("lastFailure","{}"));}catch(Exception ignored){return new JSONObject();}
    }
    public String historyText(){
        JSONObject failure=lastFailure();if(failure.length()==0)return "尚无保存的输入失败记录";
        StringBuilder text=new StringBuilder("上次输入失败（拔下设备后仍保留）\n");
        text.append(failure.optString("time")).append('\n').append(failure.optString("message")).append('\n');
        text.append("当时系统识别：").append(names(failure.optJSONArray("systemDevices"))).append('\n');
        JSONObject scan=failure.optJSONObject("inputDiagnostics");
        if(scan!=null){
            text.append("Root扫描：").append(scan.optInt("nodeCount")).append("个节点，识别键鼠 ").append(scan.optInt("acceptedCount")).append("个\n");
            if(!scan.optString("scanError").isEmpty())text.append("读取问题：").append(scan.optString("scanError")).append('\n');
            if(!scan.optString("lastFault").isEmpty())text.append("具体故障：").append(scan.optString("lastFault")).append('\n');
            text.append("内核丢事件：").append(scan.optLong("syncDrops"))
                    .append("；设备断开：").append(scan.optLong("disconnects"))
                    .append("；读取错误：").append(scan.optLong("readErrors")).append('\n');
            JSONArray nodes=scan.optJSONArray("nodes");
            if(nodes!=null)for(int i=0;i<nodes.length();i++){
                JSONObject n=nodes.optJSONObject(i);if(n==null)continue;
                text.append(n.optString("path")).append(' ').append(n.optString("name")).append("：").append(n.optString("reason")).append('\n');
                if(!n.optString("readError").isEmpty())text.append("  ").append(n.optString("readError")).append('\n');
                if(!n.optString("grabError").isEmpty())text.append("  独占失败：").append(n.optString("grabError")).append('\n');
            }
            if(scan.optBoolean("truncated"))text.append("设备清单已截断\n");
        }
        return text.toString();
    }
    private static String names(JSONArray devices){
        if(devices==null||devices.length()==0)return "无";
        StringBuilder text=new StringBuilder();for(int i=0;i<devices.length();i++){
            JSONObject d=devices.optJSONObject(i);if(d==null)continue;if(text.length()>0)text.append("、");text.append(d.optString("name","未命名设备"));
        }
        return text.length()==0?"无":text.toString();
    }
    public static String deviceSummary(JSONObject detail,JSONArray system,boolean rootConnected){
        JSONArray accepted=detail.optJSONArray("devices");
        JSONObject scan=detail.optJSONObject("inputDiagnostics");String issue=scan==null?"":scan.optString("scanError");
        if(rootConnected&&accepted!=null&&accepted.length()>0)return "Root已识别键鼠："+names(accepted)+(issue.isEmpty()?"":"\n部分输入节点读取失败，详情见诊断");
        if(rootConnected&&!issue.isEmpty())return "Root尚无可用键鼠；扫描存在读取错误："+issue+"\n请查看诊断中的逐设备结果";
        if(system.length()>0)return "系统已识别："+names(system)+(rootConnected?"\nRoot尚未识别可映射键鼠，请查看诊断":"\nRoot未激活，尚未验证输入节点");
        return rootConnected?"当前未识别到可映射键鼠；扫描详情见本地诊断":"系统当前未检测到外接键鼠";
    }
}
