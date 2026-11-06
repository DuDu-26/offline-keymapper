package cn.offline.mapper.engine;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Random;

/**
 * Reconstructed coordinate generator for the supplied 26.1.17 artifact.
 * Evidence: research/joystick-deep/algorithm, original DEX SHA-256 457095c5…435fcf.
 * Float evaluation order, RNG call order, asymmetric integer jitter and all five
 * path branches are deliberate. This class makes no account-safety claim.
 */
public final class JoystickTrajectory {
    public interface MoveSink { void move(int pointerId, int x, int y) throws Exception; }
    public interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    public static final Sleeper REAL_SLEEP = Thread::sleep;

    public static final class Params {
        public final int internalMode, angleMin, angleMax, endpointJitter;
        public final double radiusMultiplierMax, bendFactor;
        public final boolean strong, twoPhase;
        private Params(int mode, int min, int max, int jitter, double multiplier,
                       double bend, boolean strong, boolean twoPhase) {
            this.internalMode=mode; this.angleMin=min; this.angleMax=max;
            this.endpointJitter=jitter; this.radiusMultiplierMax=multiplier;
            this.bendFactor=bend; this.strong=strong; this.twoPhase=twoPhase;
        }
        public Params withAngles(int min, int max) {
            if(min < -180 || max > 180 || min > max)
                throw new IllegalArgumentException("角度范围须在 -180～180 度且下限不大于上限");
            return new Params(internalMode,min,max,endpointJitter,radiusMultiplierMax,bendFactor,strong,twoPhase);
        }
    }
    public static Params preset(int mode, boolean twoPhase) {
        if(mode==1)return new Params(1,-15,15,10,2.2d,0.6d,true,twoPhase);
        if(mode==2)return new Params(2,-6,6,3,1.4d,0.18d,false,twoPhase);
        return new Params(0,-12,12,6,1.8d,0.35d,false,twoPhase);
    }
    private static int directionIndex(int direction) {
        if(direction==0)throw new NullPointerException("direction");
        return direction-1;
    }
    private static float weighted(Random random,float factor,float base,float scale) {
        return ((random.nextFloat()*factor)+base)*scale;
    }
    public static Runnable direct(Point current,Point center,float radius,int direction,int id,MoveSink sink) {
        int x=center.x,y=center.y;
        float diagonal=(float)(Math.sqrt(2.0d)/2.0d);
        switch(directionIndex(direction)) {
            case 0:y-=(int)radius;break;
            case 1:y+=(int)radius;break;
            case 2:x+=(int)radius;break;
            case 3:x-=(int)radius;break;
            case 4:{int d=(int)(radius*diagonal);x+=d;y-=d;break;}
            case 5:{int d=(int)(radius*diagonal);x+=d;y+=d;break;}
            case 6:{int d=(int)(radius*diagonal);x-=d;y+=d;break;}
            case 7:{int d=(int)(radius*diagonal);x-=d;y-=d;break;}
            default:break;
        }
        final int targetX=x,targetY=y;
        return ()->{synchronized(current){
            if(Thread.currentThread().isInterrupted())return;
            current.set(targetX,targetY);
            try {sink.move(id,targetX,targetY);} catch(Exception e){throw new IllegalStateException("摇杆坐标发送失败",e);}
        }};
    }
    public static Motion random(Point current,Point center,float radius,int direction,int id,
                                int width,int height,Params params,Random targetRandom,
                                Random pathRandom,MoveSink sink,Sleeper sleeper) {
        int d=directionIndex(direction);
        double angle=d!=0?d!=1?d!=3?d!=4?d!=5?d!=6?d!=7?0.0d:2.356194490192345d:-2.356194490192345d:-0.7853981633974483d:0.7853981633974483d:3.141592653589793d:-1.5707963267948966d:1.5707963267948966d;
        double radians=Math.toRadians(targetRandom.nextInt((params.angleMax-params.angleMin)+1)+params.angleMin)+angle;
        double radial=((params.radiusMultiplierMax-1.0d)*targetRandom.nextDouble())+1.0d;
        double radiusDouble=radius;
        double radialDistance=radiusDouble*radial;
        int x=center.x+(int)(Math.cos(radians)*radialDistance);
        int y=center.y-(int)(Math.sin(radians)*radialDistance);
        int jitter=params.endpointJitter;
        Point target=new Point(x+(targetRandom.nextInt(jitter*2)-jitter),y+(targetRandom.nextInt(jitter*2)-jitter));
        float spread=(float)Math.max(params.bendFactor*radiusDouble,(radial-1.0d)*radiusDouble);
        Point settleTarget=null;
        float settleSpread=0.0f;
        if(params.twoPhase) {
            double settleAngle=Math.toRadians(targetRandom.nextInt(9)-4)+angle;
            double settleRadius=((targetRandom.nextDouble()*0.07d)+0.95d)*radiusDouble;
            settleTarget=new Point(center.x+(int)(Math.cos(settleAngle)*settleRadius),center.y-(int)(Math.sin(settleAngle)*settleRadius));
            settleSpread=weighted(targetRandom,0.15f,0.15f,radius);
        }
        return new Motion(current,target,id,spread,settleTarget,settleSpread,params.strong,width,height,pathRandom,sink,sleeper,-1,-1);
    }

    public static final class Motion implements Runnable {
        public final Point current,target,settleTarget;
        public final int pointerId,durationMs,pointCount;
        public final Random random;
        public final float spread,settleSpread;
        public final boolean strong;
        public final Rect bounds;
        private final MoveSink sink;
        private final Sleeper sleeper;
        /** Negative timing overrides preserve the original constructor draws. */
        public Motion(Point current,Point target,int id,float spread,Point settleTarget,float settleSpread,
                      boolean strong,int width,int height,Random random,MoveSink sink,Sleeper sleeper,
                      int durationOverride,int pointCountOverride) {
            this.current=current;this.target=new Point(target.x,target.y);this.pointerId=id;
            this.random=random;int duration=random.nextInt(13)+33;int count=random.nextInt(16)+15;
            this.durationMs=durationOverride>=0?durationOverride:duration;
            this.pointCount=pointCountOverride>=0?pointCountOverride:count;
            if(this.durationMs<=0 || this.pointCount<=0)throw new IllegalArgumentException("无效轨迹时长或点数");
            this.spread=spread;this.settleTarget=settleTarget==null?null:new Point(settleTarget.x,settleTarget.y);
            this.settleSpread=settleSpread;this.strong=strong;
            this.bounds=new Rect(10,10,width-10,height-10);this.sink=sink;this.sleeper=sleeper;
        }
        @Override public void run() {
            try {play();}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){throw new IllegalStateException("摇杆轨迹执行失败",e);}
        }
        public static PointF bezier3(float f2, PointF pointF, PointF pointF2, PointF pointF3, PointF pointF4) {
            float f7 = 1.0f - f2;
            float f8 = f2 * f2;
            float f9 = f7 * f7;
            float f10 = f9 * f7;
            float f11 = f8 * f2;
            float f12 = f9 * 3.0f * f2;
            float f13 = f7 * 3.0f * f8;
            return new PointF((pointF4.x * f11) + (pointF3.x * f13) + (pointF2.x * f12) + (pointF.x * f10), (f11 * pointF4.y) + (f13 * pointF3.y) + (f12 * pointF2.y) + (f10 * pointF.y));
        }

        public static int[] splitDurations(int i7, int i8) {
            int[] iArr = new int[i8];
            int i9 = i7 / i8;
            int i10 = i7 % i8;
            int i11 = 0;
            if (i10 == 0) {
                while (i11 < i8) {
                    iArr[i11] = i9;
                    i11++;
                }
            } else {
                float f2 = (float) i8 / (float) i10;
                float f7 = 0.0f;
                int i12 = 0;
                while (i11 < i8) {
                    iArr[i11] = i9;
                    float f8 = (1.0f / f2) + f7;
                    if (f8 >= 1.0f && i12 < i10) {
                        iArr[i11] = i9 + 1;
                        f8 -= 1.0f;
                        i12++;
                    }
                    f7 = f8;
                    i11++;
                }
                while (i12 < i10) {
                    int i13 = (i8 - 1) - (i10 - i12);
                    iArr[i13] = iArr[i13] + 1;
                    i12++;
                }
            }
            return iArr;
        }

        public static float ease(float f2) {
            if (f2 < 0.5f) {
                return 4.0f * f2 * f2 * f2;
            }
            float f7 = (f2 * 2.0f) - 2.0f;
            return (((f7 * f7) * f7) / 2.0f) + 1.0f;
        }

        public static PointF bezier4(float f2, PointF pointF, PointF pointF2, PointF pointF3, PointF pointF4, PointF pointF5) {
            float f7 = 1.0f - f2;
            float f8 = f2 * f2;
            float f9 = f7 * f7;
            float f10 = f9 * f7;
            float f11 = f8 * f2;
            float f12 = f10 * f7;
            float f13 = f11 * f2;
            float f14 = f10 * 4.0f * f2;
            float f15 = f9 * 6.0f * f8;
            float f16 = f7 * 4.0f * f11;
            return new PointF((pointF5.x * f13) + (pointF4.x * f16) + (pointF3.x * f15) + (pointF2.x * f14) + (pointF.x * f12), (f13 * pointF5.y) + (f16 * pointF4.y) + (f15 * pointF3.y) + (f14 * pointF2.y) + (f12 * pointF.y));
        }

        private boolean emit(int i7, int i8) throws Exception {
            synchronized (this.current) {
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        return false;
                    }
                    Point point = this.current;
                    point.x = i7;
                    point.y = i8;
                    sink.move(this.pointerId, i7, i8);
                    return true;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }

        private void play() throws Exception {
            int i7;
            float f2;
            float f7;
            float f8;
            float fG;
            float fG2;
            float f9;
            float f10;
            float fG3;
            float f11;
            float f12;
            float f13;
            float f14;
            float fG4;
            float f15;
            float fG5;
            int iNextInt;
            PointF pointF;
            float fG6;
            float fG7;
            float f16;
            float f17;
            PointF pointFA;
            int i8 = this.pointCount;
            Rect rect = this.bounds;
            Random random = this.random;
            ArrayList<Point> arrayList = new ArrayList<>();
            Point point = this.current;
            PointF pointF2 = new PointF(point.x, point.y);
            Point point2 = this.target;
            PointF pointF3 = new PointF(point2.x, point2.y);
            float f18 = pointF3.x - pointF2.x;
            float f19 = pointF3.y - pointF2.y;
            float fSqrt = (float) Math.sqrt((f19 * f19) + (f18 * f18));
            boolean z6 = this.strong;
            float f20 = this.spread;
            float fG8 = z6 ? weighted(random, 0.6f, 0.9f, f20) : weighted(random, 0.4f, 0.8f, f20);
            float f21 = f20 * 2.0f;
            if (fSqrt < f21) {
                fG8 *= Math.max(0.5f, fSqrt / f21);
            }
            float f22 = fG8;
            float fNextFloat = random.nextFloat();
            // Original branch 1: midpoint arc controls, sampled as a quartic Bézier.
            if (fNextFloat < (z6 ? 0.3f : 0.15f) && fSqrt > f21) {
                boolean zNextBoolean = random.nextBoolean();
                float fMin = z6 ? Math.min(f22 * 1.8f, fSqrt * 0.85f) : Math.min(f22 * 1.5f, fSqrt * 0.7f);
                ArrayList<PointF> arrayList2 = new ArrayList<>();
                PointF pointF4 = new PointF((pointF2.x + pointF3.x) / 2.0f, (pointF2.y + pointF3.y) / 2.0f);
                float fAtan2 = (float) Math.atan2(pointF3.y - pointF2.y, pointF3.x - pointF2.x);
                float fG9 = z6 ? weighted(random, 0.8f, 1.2f, 3.1415927f) : weighted(random, 0.5f, 1.0f, 3.1415927f);
                int i9 = 0;
                while (i9 < 5) {
                    boolean z7 = zNextBoolean;
                    PointF pointF5 = pointF2;
                    float f23 = (float) i9 / 4.0f;
                    float f24 = (fAtan2 - 1.5707964f) + (z7 ? (-fG9) * f23 : fG9 * f23);
                    float fG10 = z6 ? weighted(random, 0.4f, 0.8f, fMin) : weighted(random, 0.2f, 0.9f, fMin);
                    boolean z8 = z6;
                    double d3 = f24;
                    arrayList2.add(new PointF((((float) Math.cos(d3)) * fG10) + pointF4.x, (((float) Math.sin(d3)) * fG10) + pointF4.y));
                    i9++;
                    zNextBoolean = z7;
                    pointF2 = pointF5;
                    z6 = z8;
                }
                int i10 = 1;
                while (true) {
                    PointF pointF6 = pointF2;
                    PointF pointF7 = pointF3;
                    if (i10 > i8) {
                        break;
                    }
                    float fC = ease((float) i10 / (float) i8);
                    if (arrayList2.size() >= 5) {
                        pointF2 = pointF6;
                        pointF3 = pointF7;
                        pointFA = bezier4(fC, pointF2, (PointF) arrayList2.get(1), (PointF) arrayList2.get(2), (PointF) arrayList2.get(3), pointF3);
                    } else {
                        pointF2 = pointF6;
                        pointF3 = pointF7;
                        pointFA = bezier3(fC, pointF2, (PointF) arrayList2.get(1), (PointF) arrayList2.get(arrayList2.size() - 2), pointF3);
                    }
                    int iRound = Math.round(pointFA.x);
                    int iRound2 = Math.round(pointFA.y);
                    if (rect != null) {
                        iRound = Math.max(rect.left, Math.min(rect.right, iRound));
                        iRound2 = Math.max(rect.top, Math.min(rect.bottom, iRound2));
                    }
                    arrayList.add(new Point(iRound, iRound2));
                    i10++;
                }
                i7 = 5;
            } else if (fNextFloat >= (z6 ? 0.5f : 0.25f) || fSqrt <= f20) {
                PointF pointF8 = pointF3;
                i7 = 5;
                // Original branch 3: alternating transverse quartic controls.
                if (fNextFloat < (z6 ? 0.75f : 0.45f)) {
                    float fAtan22 = ((float) Math.atan2(f19, f18)) + (random.nextBoolean() ? 1.5707964f : -1.5707964f);
                    float fG11 = z6 ? weighted(random, 0.7f, 1.4f, f22) : weighted(random, 0.5f, 1.0f, f22);
                    float fG12 = z6 ? weighted(random, 0.6f, 1.2f, f22) : weighted(random, 0.4f, 0.8f, f22);
                    double d7 = fAtan22;
                    PointF pointF9 = new PointF((((float) Math.cos(d7)) * fG11) + (f18 * 0.2f) + pointF2.x, (((float) Math.sin(d7)) * fG11) + (f19 * 0.2f) + pointF2.y);
                    PointF pointF10 = new PointF(((f18 * 0.5f) + pointF2.x) - (((float) Math.cos(d7)) * fG12), ((f19 * 0.5f) + pointF2.y) - (((float) Math.sin(d7)) * fG12));
                    float f25 = fG11;
                    PointF pointF11 = new PointF((((float) Math.cos(d7)) * f25 * 0.7f) + (f18 * 0.8f) + pointF2.x, (((float) Math.sin(d7)) * f25 * 0.7f) + (f19 * 0.8f) + pointF2.y);
                    int i11 = 1;
                    while (i11 <= i8) {
                        PointF pointF12 = pointF8;
                        PointF pointFD = bezier4(ease((float) i11 / (float) i8), pointF2, pointF9, pointF10, pointF11, pointF12);
                        PointF pointF13 = pointF9;
                        int iRound3 = Math.round(pointFD.x);
                        int iRound4 = Math.round(pointFD.y);
                        if (rect != null) {
                            iRound3 = Math.max(rect.left, Math.min(rect.right, iRound3));
                            iRound4 = Math.max(rect.top, Math.min(rect.bottom, iRound4));
                        }
                        arrayList.add(new Point(iRound3, iRound4));
                        i11++;
                        pointF8 = pointF12;
                        pointF9 = pointF13;
                    }
                // Original branch 4: opposite-side cubic controls.
                } else if (fNextFloat < (z6 ? 0.9f : 0.65f)) {
                    float fAtan23 = (float) Math.atan2(f19, f18);
                    float f26 = fAtan23 + 1.5707964f;
                    float f27 = fAtan23 - 1.5707964f;
                    if (z6) {
                        fG4 = weighted(random, 0.7f, 1.3f, f22);
                        f14 = 0.5f;
                    } else {
                        f14 = 0.5f;
                        fG4 = weighted(random, 0.5f, 1.0f, f22);
                    }
                    if (z6) {
                        f15 = 0.6f;
                        fG5 = weighted(random, 0.6f, 1.1f, f22);
                    } else {
                        f15 = 0.6f;
                        fG5 = weighted(random, f14, 0.8f, f22);
                    }
                    float fNextFloat2 = (random.nextFloat() * 0.2f) + 0.2f;
                    float fNextFloat3 = (random.nextFloat() * 0.2f) + f15;
                    float f28 = fG5;
                    double d8 = f26;
                    PointF pointF14 = new PointF((((float) Math.cos(d8)) * fG4) + (f18 * fNextFloat2) + pointF2.x, (((float) Math.sin(d8)) * fG4) + (f19 * fNextFloat2) + pointF2.y);
                    double d9 = f27;
                    PointF pointF15 = new PointF((((float) Math.cos(d9)) * f28) + (f18 * fNextFloat3) + pointF2.x, (((float) Math.sin(d9)) * f28) + (fNextFloat3 * f19) + pointF2.y);
                    for (int i12 = 1; i12 <= i8; i12++) {
                        PointF pointFA2 = bezier3(ease((float) i12 / (float) i8), pointF2, pointF14, pointF15, pointF8);
                        int iRound5 = Math.round(pointFA2.x);
                        int iRound6 = Math.round(pointFA2.y);
                        if (rect != null) {
                            iRound5 = Math.max(rect.left, Math.min(rect.right, iRound5));
                            iRound6 = Math.max(rect.top, Math.min(rect.bottom, iRound6));
                        }
                        arrayList.add(new Point(iRound5, iRound6));
                    }
                } else {
                    // Original branch 5: same-side or opposite-side cubic controls.
                    float fAtan24 = (float) Math.atan2(f19, f18);
                    if (random.nextBoolean()) {
                        f10 = fAtan24 + (random.nextBoolean() ? 1.5707964f : -1.5707964f);
                        if (z6) {
                            f11 = 0.6f;
                            fG3 = weighted(random, 0.6f, 1.2f, f22);
                            f12 = 0.4f;
                            f13 = 1.0f;
                        } else {
                            f11 = 0.6f;
                            f12 = 0.4f;
                            f13 = 1.0f;
                            fG3 = weighted(random, 0.4f, 1.0f, f22);
                        }
                        fG2 = z6 ? weighted(random, f11, f13, f22) : weighted(random, f12, 0.9f, f22);
                        f2 = 0.6f;
                        f9 = f10;
                    } else {
                        boolean zNextBoolean2 = random.nextBoolean();
                        float f29 = (zNextBoolean2 ? 1.5707964f : -1.5707964f) + fAtan24;
                        float f30 = fAtan24 + (zNextBoolean2 ? -1.5707964f : 1.5707964f);
                        if (z6) {
                            f2 = 0.6f;
                            fG = weighted(random, 0.6f, 1.2f, f22);
                            f7 = 0.4f;
                            f8 = 1.0f;
                        } else {
                            f2 = 0.6f;
                            f7 = 0.4f;
                            f8 = 1.0f;
                            fG = weighted(random, 0.4f, 1.0f, f22);
                        }
                        fG2 = z6 ? weighted(random, f2, f8, f22) : weighted(random, f7, 0.8f, f22);
                        f9 = f30;
                        f10 = f29;
                        fG3 = fG;
                    }
                    float fNextFloat4 = (random.nextFloat() * 0.15f) + 0.25f;
                    float fNextFloat5 = (random.nextFloat() * 0.15f) + f2;
                    float f31 = fG2;
                    float f32 = fG3;
                    double d10 = f10;
                    PointF pointF16 = new PointF((((float) Math.cos(d10)) * f32) + (f18 * fNextFloat4) + pointF2.x, (((float) Math.sin(d10)) * f32) + (f19 * fNextFloat4) + pointF2.y);
                    double d11 = f9;
                    PointF pointF17 = new PointF((((float) Math.cos(d11)) * f31) + (f18 * fNextFloat5) + pointF2.x, (((float) Math.sin(d11)) * f31) + (fNextFloat5 * f19) + pointF2.y);
                    for (int i13 = 1; i13 <= i8; i13++) {
                        PointF pointFA3 = bezier3(ease((float) i13 / (float) i8), pointF2, pointF16, pointF17, pointF8);
                        int iRound7 = Math.round(pointFA3.x);
                        int iRound8 = Math.round(pointFA3.y);
                        if (rect != null) {
                            iRound7 = Math.max(rect.left, Math.min(rect.right, iRound7));
                            iRound8 = Math.max(rect.top, Math.min(rect.bottom, iRound8));
                        }
                        arrayList.add(new Point(iRound7, iRound8));
                    }
                }
            } else {
                // Original branch 2: shrinking spiral nodes and noisy segment interpolation.
                float fMin2 = z6 ? Math.min(f22 * 1.3f, fSqrt * 0.6f) : Math.min(f22, fSqrt * 0.5f);
                ArrayList<PointF> arrayList3 = new ArrayList<>();
                float fNextFloat6 = (random.nextFloat() * 0.4f) + 0.3f;
                float f33 = 1.0f - fNextFloat6;
                PointF pointF18 = new PointF((pointF3.x * fNextFloat6) + (pointF2.x * f33), (pointF3.y * fNextFloat6) + (pointF2.y * f33));
                if (z6) {
                    i7 = 5;
                    iNextInt = random.nextInt(4) + 5;
                } else {
                    i7 = 5;
                    iNextInt = 4 + random.nextInt(3);
                }
                float fG13 = z6 ? weighted(random, 2.0f, 3.0f, 3.1415927f) : (random.nextFloat() + 2.0f) * 3.1415927f;
                int i14 = 0;
                while (i14 < iNextInt) {
                    float f34 = (float) i14 / (float) (iNextInt - 1);
                    float f35 = fG13 * f34;
                    float f36 = (1.0f - f34) * fMin2;
                    PointF pointF19 = pointF3;
                    double d12 = f35;
                    arrayList3.add(new PointF((((float) Math.cos(d12)) * f36) + pointF18.x, (((float) Math.sin(d12)) * f36) + pointF18.y));
                    i14++;
                    pointF3 = pointF19;
                }
                PointF pointF20 = pointF3;
                int size = arrayList3.size();
                int i15 = size - 1;
                int i16 = i8 / i15;
                int i17 = 0;
                while (i17 < i15) {
                    PointF pointF21 = i17 == 0 ? pointF2 : (PointF) arrayList3.get(i17);
                    PointF pointF22 = i17 == size + (-2) ? pointF20 : (PointF) arrayList3.get(i17 + 1);
                    int i18 = 1;
                    while (i18 <= i16) {
                        float f37 = (float) i18 / (float) i16;
                        float f38 = pointF21.x;
                        float fNextFloat7 = ((random.nextFloat() - 0.5f) * 2.0f) + ((pointF22.x - f38) * f37) + f38;
                        float f39 = pointF21.y;
                        int i19 = i16;
                        float fNextFloat8 = ((random.nextFloat() - 0.5f) * 2.0f) + ((pointF22.y - f39) * f37) + f39;
                        if (rect != null) {
                            fNextFloat7 = Math.max(rect.left, Math.min(rect.right, fNextFloat7));
                            fNextFloat8 = Math.max(rect.top, Math.min(rect.bottom, fNextFloat8));
                        }
                        arrayList.add(new Point(Math.round(fNextFloat7), Math.round(fNextFloat8)));
                        i18++;
                        i16 = i19;
                    }
                    i17++;
                }
            }
            // Preserve sleep-before-MOVE order and the original actual point count.
            int[] iArrB = splitDurations(this.durationMs, arrayList.size());
            for (int i20 = 0; i20 < arrayList.size(); i20++) {
                Point point3 = (Point) arrayList.get(i20);
                try {
                    sleeper.sleep(iArrB[i20]);
                    if (!emit(point3.x, point3.y)) {
                        return;
                    }
                } catch (InterruptedException unused) {
                    return;
                }
            }
            Point point4 = this.target;
            Point point5 = new Point(point4.x, point4.y);
            // Optional return toward the nominal joystick edge after the first stroke.
            if (this.settleTarget != null) {
                sleeper.sleep(this.random.nextInt(46) + 25);
                synchronized (this.current) {
                    Point point6 = this.current;
                    pointF = new PointF(point6.x, point6.y);
                }
                Point point7 = this.settleTarget;
                PointF pointF23 = new PointF(point7.x, point7.y);
                int iNextInt2 = this.random.nextInt(7) + 8;
                int iNextInt3 = this.random.nextInt(36) + 45;
                Rect rect2 = this.bounds;
                ArrayList<Point> arrayList4 = new ArrayList<>();
                float f40 = pointF23.x - pointF.x;
                float f41 = pointF23.y - pointF.y;
                float fSqrt2 = (float) Math.sqrt((f41 * f41) + (f40 * f40));
                float f42 = this.settleSpread;
                Random random2 = this.random;
                float fG14 = weighted(random2, 0.5f, 0.6f, f42);
                float f43 = f42 * 2.0f;
                if (fSqrt2 < f43 && f42 > 0.0f) {
                    fG14 *= Math.max(0.3f, fSqrt2 / f43);
                }
                float fAtan25 = (float) Math.atan2(f41, f40);
                if (random2.nextBoolean()) {
                    f17 = fAtan25 + (random2.nextBoolean() ? 1.5707964f : -1.5707964f);
                    fG6 = weighted(random2, 0.3f, 0.8f, fG14);
                    fG7 = weighted(random2, 0.3f, 0.6f, fG14);
                    f16 = f17;
                } else {
                    boolean zNextBoolean3 = random2.nextBoolean();
                    float f44 = (zNextBoolean3 ? 1.5707964f : -1.5707964f) + fAtan25;
                    float f45 = zNextBoolean3 ? -1.5707964f : 1.5707964f;
                    fG6 = weighted(random2, 0.3f, 0.7f, fG14);
                    fG7 = weighted(random2, 0.3f, 0.5f, fG14);
                    f16 = fAtan25 + f45;
                    f17 = f44;
                }
                float fNextFloat9 = (random2.nextFloat() * 0.15f) + 0.25f;
                float fNextFloat10 = (random2.nextFloat() * 0.15f) + 0.6f;
                float f46 = (f40 * fNextFloat9) + pointF.x;
                double d13 = f17;
                PointF pointF24 = new PointF((((float) Math.cos(d13)) * fG6) + f46, (((float) Math.sin(d13)) * fG6) + (f41 * fNextFloat9) + pointF.y);
                double d14 = f16;
                PointF pointF25 = new PointF((((float) Math.cos(d14)) * fG7) + (f40 * fNextFloat10) + pointF.x, (((float) Math.sin(d14)) * fG7) + (f41 * fNextFloat10) + pointF.y);
                for (int i21 = 1; i21 <= iNextInt2; i21++) {
                    PointF pointFA4 = bezier3(ease((float) i21 / (float) iNextInt2), pointF, pointF24, pointF25, pointF23);
                    arrayList4.add(new Point(Math.max(rect2.left, Math.min(rect2.right, Math.round(pointFA4.x))), Math.max(rect2.top, Math.min(rect2.bottom, Math.round(pointFA4.y)))));
                }
                int[] iArrB2 = splitDurations(iNextInt3, arrayList4.size());
                for (int i22 = 0; i22 < arrayList4.size(); i22++) {
                    Point point8 = (Point) arrayList4.get(i22);
                    sleeper.sleep(iArrB2[i22]);
                    if (!emit(point8.x, point8.y)) {
                        return;
                    }
                }
                Point point9 = this.settleTarget;
                point5 = new Point(point9.x, point9.y);
            }
            boolean z9 = this.strong;
            int i23 = z9 ? 120 : 150;
            int i24 = z9 ? 260 : 300;
            int i25 = z9 ? 12 : i7;
            // Held-key noise is always relative to the settled target, never a random walk.
            while (!Thread.currentThread().isInterrupted()) {
                sleeper.sleep(this.random.nextInt((i24 - i23) + 1) + i23);
                int i26 = (i25 * 2) + 1;
                int iNextInt4 = this.random.nextInt(i26) - i25;
                int iNextInt5 = this.random.nextInt(i26) - i25;
                int iMax = point5.x + iNextInt4;
                int iMax2 = point5.y + iNextInt5;
                Rect rect3 = this.bounds;
                if (rect3 != null) {
                    iMax = Math.max(rect3.left, Math.min(rect3.right, iMax));
                    Rect rect4 = this.bounds;
                    iMax2 = Math.max(rect4.top, Math.min(rect4.bottom, iMax2));
                }
                if (!emit(iMax, iMax2)) {
                    return;
                }
            }
        }
    }
    private JoystickTrajectory() {}
}
