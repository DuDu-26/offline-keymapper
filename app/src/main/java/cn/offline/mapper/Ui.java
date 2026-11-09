package cn.offline.mapper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Shared visual language: colours, type scale, surfaces and common controls. */
public final class Ui {
    public static final int BG = 0xff0b0d12, SURFACE = 0xff141821, SURFACE_2 = 0xff1c212c, SURFACE_3 = 0xff252b38,
            STROKE = 0xff262c38, TEXT = 0xffeef1f6, MUTED = 0xff8b93a3, FAINT = 0xff5d6575,
            ACCENT = 0xff5b8cff, ACCENT_DARK = 0xff2f5fd6, ON_ACCENT = 0xffffffff,
            SUCCESS = 0xff3dd68c, WARN = 0xfff5b547, DANGER = 0xffff5d6c;

    private Ui() {}

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    public static int alpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }

    public static GradientDrawable shape(Context c, int color, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    /** Background with a touch ripple clipped to the same rounded shape. */
    public static Drawable pressable(Context c, int color, float radius, int stroke) {
        GradientDrawable mask = shape(c, Color.WHITE, radius, 0);
        return new RippleDrawable(ColorStateList.valueOf(alpha(Color.WHITE, 36)), shape(c, color, radius, stroke), mask);
    }

    public static TextView text(Context c, CharSequence value, float size, int color) {
        TextView v = new TextView(c);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    public static TextView title(Context c, CharSequence value, float size) {
        TextView v = text(c, value, size, TEXT);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    public static TextView overline(Context c, CharSequence value) {
        TextView v = text(c, value, 12, MUTED);
        v.setLetterSpacing(.06f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    public static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }

    public static LinearLayout card(Context c) {
        LinearLayout v = column(c);
        v.setBackground(shape(c, SURFACE, 20, STROKE));
        int p = dp(c, 18); v.setPadding(p, p, p, p);
        return v;
    }

    public static LinearLayout.LayoutParams fill(int height) { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height); }
    public static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    public static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }

    public static <T extends LinearLayout.LayoutParams> T margins(Context c, T lp, int l, int t, int r, int b) {
        lp.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)); return lp;
    }

    public enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    /** Full-width or wrap button with optional leading icon. */
    public static LinearLayout button(Context c, String label, int icon, Kind kind, Runnable action) {
        LinearLayout b = row(c);
        b.setGravity(Gravity.CENTER);
        int bg, fg, stroke = 0;
        switch (kind) {
            case PRIMARY: bg = ACCENT; fg = ON_ACCENT; break;
            case DANGER: bg = alpha(DANGER, 28); fg = DANGER; break;
            case GHOST: bg = Color.TRANSPARENT; fg = TEXT; stroke = STROKE; break;
            default: bg = SURFACE_3; fg = TEXT; break;
        }
        b.setBackground(pressable(c, bg, 14, stroke));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        if (icon != 0) {
            ImageView i = new ImageView(c); i.setImageDrawable(new Icon(c, icon, fg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)); lp.rightMargin = dp(c, 8);
            b.addView(i, lp);
        }
        TextView t = text(c, label, 15, fg); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setSingleLine(true);
        b.addView(t, wrap());
        b.setClickable(true); b.setFocusable(true);
        b.setContentDescription(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Small rounded label, e.g. a status or a tag. */
    public static TextView chip(Context c, String label, int color) {
        TextView t = text(c, label, 12, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(shape(c, alpha(color, 30), 999, 0));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setSingleLine(true);
        return t;
    }

    /** Status chip with a coloured dot in front. */
    public static LinearLayout statusChip(Context c, String label, int color) {
        LinearLayout r = row(c);
        r.setBackground(shape(c, alpha(color, 28), 999, 0));
        r.setPadding(dp(c, 10), dp(c, 5), dp(c, 12), dp(c, 5));
        View dot = new View(c); dot.setBackground(shape(c, color, 999, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 8), dp(c, 8)); lp.rightMargin = dp(c, 7);
        r.addView(dot, lp);
        TextView t = text(c, label, 12, color); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setSingleLine(true);
        r.addView(t);
        return r;
    }

    /** Rounded square with an icon, used at the start of list rows. */
    public static FrameLayout iconTile(Context c, int icon, int color, int size) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(shape(c, alpha(color, 34), 12, 0));
        ImageView i = new ImageView(c); i.setImageDrawable(new Icon(c, icon, color));
        int inner = Math.round(size * .5f);
        f.addView(i, new FrameLayout.LayoutParams(dp(c, inner), dp(c, inner), Gravity.CENTER));
        return f;
    }

    /** List row: icon tile, title, optional subtitle and trailing view. */
    public static LinearLayout listRow(Context c, int icon, int iconColor, String title, String subtitle, View trailing, Runnable action) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        r.setMinimumHeight(dp(c, 64));
        if (action != null) { r.setBackground(pressable(c, Color.TRANSPARENT, 16, 0)); r.setClickable(true); r.setOnClickListener(v -> action.run()); }
        if (icon != 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)); lp.rightMargin = dp(c, 14);
            r.addView(iconTile(c, icon, iconColor, 40), lp);
        }
        LinearLayout texts = column(c);
        TextView t = title(c, title, 15); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(c, subtitle, 13, MUTED); s.setMaxLines(2); s.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(s, margins(c, wrap(), 0, 2, 0, 0));
        }
        r.addView(texts, weight(1));
        if (trailing != null) r.addView(trailing, margins(c, wrap(), 10, 0, 0, 0));
        return r;
    }

    public static ImageView chevron(Context c) {
        ImageView i = new ImageView(c); i.setImageDrawable(new Icon(c, Icon.CHEVRON, FAINT));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        return i;
    }

    public static View divider(Context c) {
        View v = new View(c); v.setBackgroundColor(STROKE);
        v.setLayoutParams(margins(c, fill(Math.max(1, dp(c, 1) / 2 + 0)), 68, 0, 14, 0));
        return v;
    }

    /** Card holding grouped list rows, separated by thin dividers. */
    public static LinearLayout group(Context c, View... rows) {
        LinearLayout g = column(c);
        g.setBackground(shape(c, SURFACE, 20, STROKE));
        g.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) g.addView(divider(c));
            g.addView(rows[i]);
        }
        return g;
    }

    public static Switch toggle(Context c, boolean checked) {
        Switch s = new Switch(c);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[] {Color.WHITE, 0xffc4cad6}));
        s.setTrackTintList(new ColorStateList(states, new int[] {ACCENT, SURFACE_3}));
        return s;
    }
}
