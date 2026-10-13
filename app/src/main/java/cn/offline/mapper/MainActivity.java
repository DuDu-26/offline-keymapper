package cn.offline.mapper;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements RootBridge.Listener {
    private static final int GREEN=0xff44d62c,BG=0xff07090b,SURFACE=0xff1f2022,MUTED=0xff9ba6b2;
    private LinearLayout page,content,nav;private TextView status,devices;private RootBridge bridge;private int tab=0;private boolean alive;private String exportSnapshot;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){if(alive){bridge.refresh();handler.postDelayed(this,2000);}}};
    @Override public void onCreate(Bundle b){super.onCreate(b);if(b!=null)exportSnapshot=b.getString("exportSnapshot");bridge=RootBridge.get(this);bridge.addListener(this);render();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("exportSnapshot",exportSnapshot);}
    @Override protected void onResume(){super.onResume();alive=true;if(bridge.running())bridge.pauseMapping();refreshStatus();handler.removeCallbacks(poll);handler.post(poll);}
    @Override protected void onPause(){super.onPause();alive=false;handler.removeCallbacks(poll);}
    @Override protected void onDestroy(){bridge.removeListener(this);super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);render();}
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private LinearLayout col(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(18),dp(14),dp(18),dp(14));c.setBackground(bg(SURFACE,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);content.addView(c,lp);return c;}
    private void button(LinearLayout parent,String label,Runnable action,boolean primary){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?0xff071106:Color.WHITE);b.setBackground(bg(primary?GREEN:0xff2d353f,12));b.setMinHeight(dp(46));b.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(9);parent.addView(b,lp);b.setOnClickListener(v->action.run());}
    private void render(){
        status=null;devices=null;page=col();page.setBackgroundColor(BG);page.setPadding(dp(18),dp(12),dp(18),0);setContentView(page);
        TextView title=text("离线键鼠映射",26,Color.WHITE);title.setTypeface(null,Typeface.BOLD);page.addView(title);page.addView(text("ROOT 本地版  /  1.0.0",12,GREEN));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content=col();content.setPadding(0,dp(18),0,dp(18));scroll.addView(content);
        nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);page.addView(nav);String[] tabs={"游戏","配置","设置"};for(int i=0;i<3;i++){final int t=i;TextView v=text(tabs[i],15,i==tab?GREEN:MUTED);v.setGravity(Gravity.CENTER);v.setPadding(0,dp(18),0,dp(18));nav.addView(v,new LinearLayout.LayoutParams(0,-2,1));v.setOnClickListener(w->{tab=t;render();});}
        if(tab==0)home();else if(tab==1)profiles();else settings();refreshStatus();
    }
    private void home(){
        LinearLayout c=card();c.addView(text("设备与激活",16,Color.WHITE));status=text(bridge.status(),14,GREEN);c.addView(status);devices=text("",12,MUTED);c.addView(devices);
        button(c,"激活 Root 服务",()->bridge.activate(),true);button(c,"启动悬浮球 / 编辑映射",()->showOverlay(true),false);
        LinearLayout quick=card();JSONObject p=ProfileStore.current(this);quick.addView(text("当前配置 · "+p.optString("name"),17,Color.WHITE));quick.addView(text("默认：WASD 移动 · F1 切换鼠标视角 · F8 紧急暂停\n长按射击、跳跃、滑动和连点均可单独设置。",13,MUTED));button(quick,"打开触控练习场",()->startActivity(new Intent(this,TouchLabActivity.class)),false);
        LinearLayout games=card();games.addView(text("我的游戏",20,GREEN));button(games,"＋ 添加已安装游戏",this::chooseGame,true);
        JSONArray a=ProfileStore.all(this);int count=0;for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);if(item.optString("package").isEmpty())continue;count++;String name=item.optString("name"),id=item.optString("id");button(games,name+"  ›",()->launchGame(id),false);}
        if(count==0)games.addView(text("添加游戏后，为每个游戏保存独立布局。配置全部保存在本机。",13,MUTED));
    }
    private void refreshStatus(){if(status!=null)status.setText(bridge.status());if(devices!=null){JSONArray a=bridge.detail().optJSONArray("devices");String line="";if(a!=null){for(int i=0;i<a.length();i++){Object d=a.opt(i);if(d instanceof JSONObject)line+=((JSONObject)d).optString("name",d.toString())+"  ";else line+=d+"  ";}}if(line.isEmpty()){for(int id:android.view.InputDevice.getDeviceIds()){android.view.InputDevice d=android.view.InputDevice.getDevice(id);if(d!=null&&!d.isVirtual()&&(Build.VERSION.SDK_INT>=29?d.isExternal():(d.getVendorId()!=0||d.getProductId()!=0))&&((d.getSources()&android.view.InputDevice.SOURCE_KEYBOARD)==android.view.InputDevice.SOURCE_KEYBOARD||(d.getSources()&android.view.InputDevice.SOURCE_MOUSE)==android.view.InputDevice.SOURCE_MOUSE))line+=d.getName()+"  ";}}devices.setText(line.isEmpty()?"尚未发现外接键鼠，可先连接蓝牙或 USB 设备":"输入设备："+line);}}
    public void onChanged(){if(alive)refreshStatus();}
    private void showOverlay(boolean editor){
        if(!Settings.canDrawOverlays(this)){new AlertDialog.Builder(this).setTitle("允许悬浮窗").setMessage("游戏内编辑和悬浮球需要此权限。允许后回到这里再次启动。").setPositiveButton("打开权限设置",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))).setNegativeButton("取消",null).show();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},40);
        Intent intent=new Intent(this,OverlayService.class);if(editor)intent.setAction(OverlayService.ACTION_EDITOR);startForegroundService(intent);
    }
    private void launchGame(String id){
        if(bridge.running())bridge.pauseMapping();ProfileStore.select(this,id);JSONObject p=ProfileStore.current(this);Intent game=getPackageManager().getLaunchIntentForPackage(p.optString("package"));if(game==null){toast("游戏已卸载或无法启动");return;}
        if(!Settings.canDrawOverlays(this)){showOverlay(false);return;}showOverlay(false);startActivity(game);toast("进入游戏后点击悬浮球编辑 / 开始映射");
    }
    private void chooseGame(){
        ProgressDialog waiting=ProgressDialog.show(this,"","正在读取已安装应用…",true,false);
        new Thread(()->{
            Intent launcher=new Intent(Intent.ACTION_MAIN);launcher.addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> infos=getPackageManager().queryIntentActivities(launcher,0);TreeMap<String,String> sorted=new TreeMap<>();for(ResolveInfo r:infos){if(!r.activityInfo.packageName.equals(getPackageName()))sorted.put(r.loadLabel(getPackageManager()).toString()+"\n"+r.activityInfo.packageName,r.activityInfo.packageName);}String[] names=sorted.keySet().toArray(new String[0]);
            runOnUiThread(()->{waiting.dismiss();if(isFinishing())return;new AlertDialog.Builder(this).setTitle("选择游戏 / 应用").setItems(names,(d,i)->{String pkg=sorted.get(names[i]);JSONObject p=ProfileStore.template(names[i].split("\n")[0],pkg);try{saveProfile(p);render();}catch(Exception problem){toast(problem.getMessage());}}).setNegativeButton("取消",null).show();});
        },"game-list").start();
    }
    private void saveProfile(JSONObject p){bridge.pauseMapping();ProfileStore.save(this,p);}
    private void profiles(){
        LinearLayout top=card();top.addView(text("本地配置",21,GREEN));top.addView(text("按游戏保存点位，导出 JSON 后可在另一台设备导入。坐标按屏幕比例保存。",13,MUTED));button(top,"＋ 新建配置",()->nameDialog("新建配置","",name->{saveProfile(ProfileStore.template(name,""));render();}),true);button(top,"导入配置文件",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,11);},false);
        JSONArray a=ProfileStore.all(this);String current=ProfileStore.current(this).optString("id");for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);LinearLayout c=card();c.addView(text((p.optString("id").equals(current)?"●  ":"")+p.optString("name"),18,p.optString("id").equals(current)?GREEN:Color.WHITE));c.addView(text(p.optJSONArray("bindings").length()+" 个点位  ·  "+(p.optString("package").isEmpty()?"通用布局":p.optString("package")),12,MUTED));button(c,"选择 / 编辑 / 导出",()->profileMenu(p),false);}
    }
    private void profileMenu(JSONObject p){String[] actions={"设为当前配置","进入屏幕编辑器","重命名","复制配置","导出 JSON","删除配置"};new AlertDialog.Builder(this).setTitle(p.optString("name")).setItems(actions,(d,i)->{try{switch(i){case 0:bridge.pauseMapping();ProfileStore.select(this,p.optString("id"));render();break;case 1:bridge.pauseMapping();ProfileStore.select(this,p.optString("id"));showOverlay(true);break;case 2:nameDialog("重命名",p.optString("name"),name->{try{p.put("name",name);saveProfile(p);render();}catch(JSONException ignored){}});break;case 3:p.put("id",UUID.randomUUID().toString()).put("name",p.optString("name")+" 副本");saveProfile(p);render();break;case 4:exportSnapshot=p.toString();Intent file=new Intent(Intent.ACTION_CREATE_DOCUMENT);file.addCategory(Intent.CATEGORY_OPENABLE);file.setType("application/json");file.putExtra(Intent.EXTRA_TITLE,"映射-"+p.optString("name")+".json");startActivityForResult(file,12);break;case 5:new AlertDialog.Builder(this).setTitle("删除此本地配置？").setMessage(p.optString("name")).setPositiveButton("删除",(x,y)->{bridge.pauseMapping();ProfileStore.delete(this,p.optString("id"));render();}).setNegativeButton("取消",null).show();break;}}catch(Exception e){toast(e.getMessage());}}).show();}
    private interface Named {void accept(String name);}
    private void nameDialog(String title,String old,Named action){EditText e=new EditText(this);e.setSingleLine(true);e.setText(old);e.setPadding(dp(20),dp(12),dp(20),dp(12));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("保存",null).setNegativeButton("取消",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String name=e.getText().toString().trim();if(name.isEmpty()||name.length()>80){e.setError("请输入1～80字的名称");return;}try{action.accept(name);dialog.dismiss();}catch(Exception problem){e.setError(problem.getMessage());}}));dialog.show();}
    private void settings(){
        LinearLayout c=card();c.addView(text("Root 激活",21,GREEN));status=text(bridge.status(),14,MUTED);c.addView(status);button(c,"请求 Root 授权并启动",bridge::activate,true);button(c,"停止映射 / 关闭 Root 服务",()->{bridge.stop();stopService(new Intent(this,OverlayService.class));},false);button(c,"悬浮窗权限",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))),false);button(c,"通知权限",()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},40);},false);button(c,"查看诊断 / 复制日志",this::showDiagnostics,false);
        LinearLayout usage=card();usage.addView(text("使用步骤",19,Color.WHITE));usage.addView(text("1. 在 Magisk / KernelSU / APatch 允许本应用 Root。\n2. 允许悬浮窗，连接 USB 或蓝牙键鼠。\n3. 添加游戏，进入游戏后用悬浮球编辑布局。\n4. 保存后开始映射；F1 切换视角，F8 随时暂停。\n5. 退出悬浮球时同时停止服务，恢复键鼠。",14,MUTED));
        LinearLayout about=card();about.addView(text("完全本地运行",19,GREEN));about.addView(text("无需账号、联网或激活码。Root 授权仅在你的手机上完成。\n\n本版包含按键、WASD 摇杆、FPS 视角、滑动、连点和配置管理。原包的云配置、图像识别和专用游戏辅助尚未复现。\n\n独立实现。REA 用于分析；KeySync 用于公开技术参考。",13,MUTED));
    }
    private void showDiagnostics(){TextView t=text(bridge.diagnostics(),12,MUTED);t.setTextIsSelectable(true);t.setPadding(dp(14),dp(10),dp(14),dp(10));ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("本地诊断").setView(s).setPositiveButton("复制",(d,w)->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("映射诊断",bridge.diagnostics()))).setNegativeButton("关闭",null).show();}
    private void toast(String s){Toast.makeText(this,s==null?"操作失败":s,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;try{if(request==11){try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>262144)throw new IOException("配置文件不得超过256KB");}JSONObject p=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));p.put("id",UUID.randomUUID().toString());ProfileStore.validate(p);bridge.pauseMapping();saveProfile(p);tab=1;render();toast("配置导入完成");}}else if(request==12){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(exportSnapshot==null)throw new IOException("导出已中断，请重新选择配置");out.write(new JSONObject(exportSnapshot).toString(2).getBytes(StandardCharsets.UTF_8));exportSnapshot=null;toast("配置已导出");}}}catch(Exception e){toast("文件操作失败："+e.getMessage());}}
}
