package cn.offline.mapper.engine;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Normal desktop pointer behavior while view lock is off. */
final class MouseCursor {
    private final TouchInjector injector;
    private final Method actionButton;
    private int width=1920,height=1080,buttons;
    private float x=960,y=540;
    private long downTime;
    MouseCursor(TouchInjector injector) throws Exception {
        this.injector=injector;actionButton=MotionEvent.class.getDeclaredMethod("setActionButton",int.class);
        actionButton.setAccessible(true);
    }
    float x(){return x;}float y(){return y;}
    void configure(int width,int height) throws Exception {cancel();this.width=width;this.height=height;x=width/2f;y=height/2f;}
    static int button(int code) {
        switch(code){case 272:return 1;case 273:return 2;case 274:return 4;case 275:return 8;case 276:return 16;default:return 0;}
    }
    void move(float dx,float dy) throws Exception {
        if(dx==0&&dy==0)return;
        x=Math.max(0,Math.min(width-1,x+dx));y=Math.max(0,Math.min(height-1,y+dy));
        emit(buttons==0?MotionEvent.ACTION_HOVER_MOVE:MotionEvent.ACTION_MOVE,0,0);
    }
    void key(int code,int value) throws Exception {
        int button=button(code);if(button==0||value==2)return;
        if(value!=0) {
            if((buttons&button)!=0)return;
            boolean first=buttons==0;buttons|=button;
            if(first){downTime=SystemClock.uptimeMillis();emit(MotionEvent.ACTION_DOWN,0,0);}
            emit(MotionEvent.ACTION_BUTTON_PRESS,button,0);
        } else {
            if((buttons&button)==0)return;
            buttons&=~button;emit(MotionEvent.ACTION_BUTTON_RELEASE,button,0);
            if(buttons==0)emit(MotionEvent.ACTION_UP,0,0);
        }
    }
    void scroll(float amount) throws Exception {if(amount!=0)emit(MotionEvent.ACTION_SCROLL,0,amount);}
    void cancel() throws Exception {
        if(buttons==0)return;
        int old=buttons;buttons=0;
        try{for(int b=1;b<=16;b<<=1)if((old&b)!=0)emit(MotionEvent.ACTION_BUTTON_RELEASE,b,0);emit(MotionEvent.ACTION_UP,0,0);}
        finally{buttons=0;}
    }
    private void emit(int action,int changedButton,float scroll) throws Exception {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=buttons==0?0:1;c.size=1;
        if(scroll!=0)c.setAxisValue(MotionEvent.AXIS_VSCROLL,scroll);
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(downTime==0?now:downTime,now,action,1,
                new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,buttons,1f,1f,-1,0,InputDevice.SOURCE_MOUSE,0);
        try{if(changedButton!=0)actionButton.invoke(event,changedButton);injector.injectEvent(event);}
        finally{event.recycle();}
    }
}
