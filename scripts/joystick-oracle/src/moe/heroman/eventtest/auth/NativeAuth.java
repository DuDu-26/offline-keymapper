package moe.heroman.eventtest.auth;

import java.util.ArrayList;

/** Test-only move recorder, with no native libraries or account/client code. */
public final class NativeAuth {
    public static final ArrayList<int[]> moves=new ArrayList<>();
    public static final ArrayList<String> stateEvents=new ArrayList<>();
    public static boolean recordingState;
    public static int limit;
    public static synchronized void reset(int count){moves.clear();limit=count;recordingState=false;}
    public static synchronized void resetState(){moves.clear();stateEvents.clear();limit=0;recordingState=true;}
    public static synchronized void state(String action,int id,int x,int y){stateEvents.add(action+":"+id+":"+x+":"+y);}
    public static synchronized void E0(int id,int x,int y){
        if(recordingState){state("M",id,x,y);return;}
        moves.add(new int[]{id,x,y});
        if(moves.size()>=limit)Thread.currentThread().interrupt();
    }
    public static synchronized int B0(int x,int y){if(!recordingState)throw new UnsupportedOperationException("Down is outside this calculation oracle");state("D",30,x,y);return 30;}
    public static int[] D0(int x,int y,int dx,int dy){throw new UnsupportedOperationException("Down is outside this calculation oracle");}
    public static synchronized void G0(int id,int x,int y){if(!recordingState)throw new UnsupportedOperationException("Up is outside this calculation oracle");state("U",id,x,y);}
}
