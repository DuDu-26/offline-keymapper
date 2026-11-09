package cn.offline.mapper.engine;

/** Drawn pointer with touch-based left click/drag; no Android mouse/hover injection. */
final class MouseCursor {
    private static final int TOUCH_ID=29;
    private final TouchInjector injector;
    private int width=1920,height=1080;
    private float x=960,y=540;
    private boolean pressed;
    MouseCursor(TouchInjector injector){this.injector=injector;}
    float x(){return x;}float y(){return y;}
    void configure(int width,int height) throws Exception {
        cancel();this.width=width;this.height=height;x=width/2f;y=height/2f;
    }
    static int button(int code) {
        switch(code){case 272:return 1;case 273:return 2;case 274:return 4;case 275:return 8;case 276:return 16;default:return 0;}
    }
    void move(float dx,float dy) throws Exception {
        if(dx==0&&dy==0)return;
        x=Math.max(0,Math.min(width-1,x+dx));y=Math.max(0,Math.min(height-1,y+dy));
        if(pressed)injector.move(TOUCH_ID,touchX(),touchY());
    }
    void key(int code,int value) throws Exception {
        if(code!=272||value==2)return;
        if(value!=0&&!pressed){injector.down(TOUCH_ID,touchX(),touchY());pressed=true;}
        else if(value==0&&pressed){try{injector.up(TOUCH_ID);}finally{pressed=false;}}
    }
    void cancel() throws Exception {
        try{if(pressed)injector.up(TOUCH_ID);}finally{pressed=false;}
    }
    // The drawn arrow tip sits four pixels inside the bitmap; touches land on the tip.
    private float touchX(){return Math.max(0,x-4);}
    private float touchY(){return Math.max(0,y-4);}
}
