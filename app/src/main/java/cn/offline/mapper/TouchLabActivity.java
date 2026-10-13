package cn.offline.mapper;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import java.io.*;
import org.json.*;

/** Visible local test surface; never forwards touches to another application. */
public class TouchLabActivity extends Activity {
    private Lab view;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);if(android.os.Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);getWindow().getAttributes().layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;}getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);view=new Lab();setContentView(view);}
    private class Lab extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final JSONArray history=new JSONArray();private String last="等待输入";private int maxPointers,count;private final java.util.Map<Integer,PointF> points=new java.util.HashMap<>();
        Lab(){super(TouchLabActivity.this);setBackgroundColor(0xff0c1117);setFocusableInTouchMode(true);try(FileOutputStream out=openFileOutput("touch-lab.json",MODE_PRIVATE)){out.write("{\"events\":0,\"maxPointers\":0,\"active\":0,\"history\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException ignored){}}
        private float dp(float n){return n*getResources().getDisplayMetrics().density;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(0xff24313f);paint.setStrokeWidth(dp(1));for(int x=0;x<getWidth();x+=dp(50))c.drawLine(x,0,x,getHeight(),paint);for(int y=0;y<getHeight();y+=dp(50))c.drawLine(0,y,getWidth(),y,paint);paint.setColor(0xff44d62c);paint.setTextSize(dp(23));c.drawText("触控练习场",dp(24),dp(52),paint);paint.setColor(0xffbcc7d4);paint.setTextSize(dp(13));c.drawText("点击悬浮球编辑点位，再开始映射。F8 暂停。",dp(24),dp(83),paint);c.drawText("事件 "+count+"  ·  最大同时触点 "+maxPointers,dp(24),dp(109),paint);c.drawText(last,dp(24),dp(137),paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(0xff44d62c);c.drawCircle(getWidth()*.18f,getHeight()*.75f,Math.min(getWidth(),getHeight())*.075f,paint);c.drawCircle(getWidth()*.87f,getHeight()*.48f,dp(28),paint);c.drawCircle(getWidth()*.85f,getHeight()*.78f,dp(28),paint);paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(12));c.drawText("WASD",getWidth()*.18f-dp(16),getHeight()*.75f+dp(4),paint);c.drawText("左键",getWidth()*.87f-dp(13),getHeight()*.48f+dp(4),paint);c.drawText("空格",getWidth()*.85f-dp(13),getHeight()*.78f+dp(4),paint);
            for(java.util.Map.Entry<Integer,PointF> e:points.entrySet()){paint.setColor(0xaaffc24a);c.drawCircle(e.getValue().x,e.getValue().y,dp(24),paint);paint.setColor(Color.BLACK);c.drawText(""+e.getKey(),e.getValue().x-dp(4),e.getValue().y+dp(4),paint);}
        }
        @Override public boolean onTouchEvent(MotionEvent e){count++;maxPointers=Math.max(maxPointers,e.getPointerCount());last=MotionEvent.actionToString(e.getAction())+"  "+Math.round(e.getX())+", "+Math.round(e.getY());points.clear();if(e.getActionMasked()!=MotionEvent.ACTION_UP&&e.getActionMasked()!=MotionEvent.ACTION_CANCEL)for(int i=0;i<e.getPointerCount();i++)if(!(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP&&i==e.getActionIndex()))points.put(e.getPointerId(i),new PointF(e.getX(i),e.getY(i)));
            try{JSONObject row=new JSONObject().put("action",e.getActionMasked()).put("index",e.getActionIndex()).put("count",e.getPointerCount()).put("eventTime",e.getEventTime()).put("downTime",e.getDownTime());JSONArray p=new JSONArray();for(int i=0;i<e.getPointerCount();i++)p.put(new JSONObject().put("id",e.getPointerId(i)).put("x",e.getX(i)).put("y",e.getY(i)));row.put("pointers",p);history.put(row);if(history.length()>300)history.remove(0);JSONObject report=new JSONObject().put("events",count).put("maxPointers",maxPointers).put("active",points.size()).put("history",history);try(FileOutputStream out=openFileOutput("touch-lab.json",MODE_PRIVATE)){out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}invalidate();return true;}
    }
}
