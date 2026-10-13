package cn.offline.mapper;

import android.view.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class BindingKey {
    private static final LinkedHashMap<Integer,String> NAMES=new LinkedHashMap<>();
    private static final Map<Integer,Integer> ANDROID=new LinkedHashMap<>();
    static {
        add(1,"ESC",KeyEvent.KEYCODE_ESCAPE); add(57,"空格",KeyEvent.KEYCODE_SPACE); add(28,"回车",KeyEvent.KEYCODE_ENTER);
        add(15,"TAB",KeyEvent.KEYCODE_TAB); add(42,"SHIFT",KeyEvent.KEYCODE_SHIFT_LEFT); add(54,"右SHIFT",KeyEvent.KEYCODE_SHIFT_RIGHT);
        add(29,"CTRL",KeyEvent.KEYCODE_CTRL_LEFT); add(97,"右CTRL",KeyEvent.KEYCODE_CTRL_RIGHT); add(56,"ALT",KeyEvent.KEYCODE_ALT_LEFT); add(100,"右ALT",KeyEvent.KEYCODE_ALT_RIGHT);
        int[] letters={30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
        for(int i=0;i<26;i++) add(letters[i],String.valueOf((char)('A'+i)),KeyEvent.KEYCODE_A+i);
        for(int i=1;i<=9;i++) add(i+1,""+i,KeyEvent.KEYCODE_1+i-1); add(11,"0",KeyEvent.KEYCODE_0);
        for(int i=0;i<10;i++) add(59+i,"F"+(i+1),KeyEvent.KEYCODE_F1+i); add(87,"F11",KeyEvent.KEYCODE_F11); add(88,"F12",KeyEvent.KEYCODE_F12);
        add(103,"上",KeyEvent.KEYCODE_DPAD_UP); add(108,"下",KeyEvent.KEYCODE_DPAD_DOWN); add(105,"左",KeyEvent.KEYCODE_DPAD_LEFT); add(106,"右",KeyEvent.KEYCODE_DPAD_RIGHT);
        add(14,"退格",KeyEvent.KEYCODE_DEL); add(111,"DELETE",KeyEvent.KEYCODE_FORWARD_DEL);
        add(272,"鼠标左键",-1); add(273,"鼠标右键",-1); add(274,"鼠标中键",-1); add(275,"鼠标侧键1",-1); add(276,"鼠标侧键2",-1);
        add(768,"滚轮上",-1); add(769,"滚轮下",-1);
    }
    private static void add(int linux,String label,int android){ NAMES.put(linux,label); if(android>=0) ANDROID.put(android,linux); }
    public static int linuxFromAndroid(int key){return ANDROID.getOrDefault(key,-1);}
    public static String label(int code){return NAMES.getOrDefault(code,"KEY_"+code);}
    public static int codeFromLabel(String text){
        String s=text.trim().toUpperCase(Locale.ROOT); if(s.equals("SPACE"))return 57; if(s.equals("ENTER"))return 28;
        if(s.equals("LMB"))return 272;if(s.equals("RMB"))return 273;if(s.equals("MMB"))return 274;
        for(Map.Entry<Integer,String> e:NAMES.entrySet())if(e.getValue().equalsIgnoreCase(s))return e.getKey();
        try{return Integer.parseInt(s.replace("KEY_",""));}catch(NumberFormatException e){return -1;}
    }
    public static String[] labels(){return NAMES.values().toArray(new String[0]);}
    private BindingKey(){}
}
