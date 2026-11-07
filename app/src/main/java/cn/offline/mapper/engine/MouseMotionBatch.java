package cn.offline.mapper.engine;

/** Accumulates one native read batch; button/wheel boundaries retain input order. */
final class MouseMotionBatch {
    interface Sink { void move(float x,float y,long time) throws Exception; }
    private final Sink sink;
    private int device=-1;
    private float x,y;
    MouseMotionBatch(Sink sink){this.sink=sink;}
    void relative(int source,int axis,int value,long time) throws Exception {
        if(axis!=0&&axis!=1)return;
        if(device!=-1&&device!=source)flush(time);
        device=source;
        if(axis==0)x+=value;else y+=value;
    }
    void flush(long time) throws Exception {
        float dx=x,dy=y;clear();
        if(dx!=0||dy!=0)sink.move(dx,dy,time);
    }
    void clear(){x=y=0;device=-1;}
}
