package g;

import android.util.Size;

/** Test-only display adapter. Never included in the delivered app. */
public final class d {
    public static int width=1920,height=1080;
    private static final d INSTANCE=new d();
    public static d C(){return INSTANCE;}
    public Size t(){return new Size(width,height);}
}
