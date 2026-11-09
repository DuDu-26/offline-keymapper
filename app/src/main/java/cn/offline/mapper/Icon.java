package cn.offline.mapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple line icons drawn on a 24×24 grid, tinted with one colour. */
public final class Icon extends Drawable {
    public static final int GAMEPAD = 1, LAYERS = 2, SETTINGS = 3, PLAY = 4, PAUSE = 5, EDIT = 6, PLUS = 7,
            KEYBOARD = 8, MOUSE = 9, POWER = 10, PULSE = 11, TARGET = 12, CHEVRON = 13, MORE = 14,
            IMPORT = 15, EXPORT = 16, COPY = 17, TRASH = 18, CHECK = 19, CLOSE = 20, SHIELD = 21,
            JOYSTICK = 22, BELL = 23, WINDOW = 24, STOP = 25, SEARCH = 26, INFO = 27, EYE = 28, HOME = 29;

    private final int type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public Icon(Context context, int type, int color) {
        this.type = type;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        canvas.save();
        canvas.translate(getBounds().left + (getBounds().width() - 24 * s) / 2, getBounds().top + (getBounds().height() - 24 * s) / 2);
        canvas.scale(s, s);
        paint.setStrokeWidth(1.8f);
        paint.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (type) {
            case GAMEPAD:
                round(canvas, 2.5f, 7, 21.5f, 17.5f, 5);
                line(canvas, 7, 10, 7, 14.5f); line(canvas, 4.75f, 12.25f, 9.25f, 12.25f);
                dot(canvas, 15.5f, 11, 1.1f); dot(canvas, 18, 13.5f, 1.1f);
                break;
            case LAYERS:
                path.moveTo(12, 3.5f); path.lineTo(21, 8); path.lineTo(12, 12.5f); path.lineTo(3, 8); path.close(); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(3, 12.5f); path.lineTo(12, 17); path.lineTo(21, 12.5f); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(3, 16.5f); path.lineTo(12, 21); path.lineTo(21, 16.5f); canvas.drawPath(path, paint);
                break;
            case SETTINGS:
                canvas.drawCircle(12, 12, 3, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    line(canvas, 12 + (float) Math.cos(a) * 6.2f, 12 + (float) Math.sin(a) * 6.2f, 12 + (float) Math.cos(a) * 8.8f, 12 + (float) Math.sin(a) * 8.8f);
                }
                canvas.drawCircle(12, 12, 6.2f, paint);
                break;
            case PLAY:
                path.moveTo(7.5f, 4.5f); path.lineTo(19, 12); path.lineTo(7.5f, 19.5f); path.close();
                fill(canvas); break;
            case PAUSE:
                round(canvas, 6.5f, 5, 9.5f, 19, 1); round(canvas, 14.5f, 5, 17.5f, 19, 1); break;
            case STOP:
                round(canvas, 6, 6, 18, 18, 2.5f); break;
            case EDIT:
                path.moveTo(4, 20); path.lineTo(4.8f, 16.2f); path.lineTo(15.5f, 5.5f); path.lineTo(18.5f, 8.5f); path.lineTo(7.8f, 19.2f); path.close();
                canvas.drawPath(path, paint); line(canvas, 13.5f, 7.5f, 16.5f, 10.5f); break;
            case PLUS:
                line(canvas, 12, 5, 12, 19); line(canvas, 5, 12, 19, 12); break;
            case KEYBOARD:
                round(canvas, 2.5f, 6, 21.5f, 18, 3);
                for (int i = 0; i < 5; i++) dot(canvas, 6 + i * 3, 10, .9f);
                for (int i = 0; i < 4; i++) dot(canvas, 7.5f + i * 3, 13, .9f);
                line(canvas, 8, 15.5f, 16, 15.5f); break;
            case MOUSE:
                round(canvas, 6.5f, 3, 17.5f, 21, 5.5f); line(canvas, 12, 6.5f, 12, 9.5f); break;
            case POWER:
                line(canvas, 12, 3.5f, 12, 11.5f);
                oval.set(4.5f, 5, 19.5f, 20); canvas.drawArc(oval, -60, 300, false, paint); break;
            case PULSE:
                path.moveTo(3, 12); path.lineTo(7, 12); path.lineTo(9.5f, 6); path.lineTo(14, 18); path.lineTo(16.5f, 12); path.lineTo(21, 12);
                canvas.drawPath(path, paint); break;
            case TARGET:
                canvas.drawCircle(12, 12, 8.5f, paint); canvas.drawCircle(12, 12, 4.5f, paint); dot(canvas, 12, 12, 1.3f); break;
            case JOYSTICK:
                canvas.drawCircle(12, 12, 8.5f, paint); canvas.drawCircle(14.5f, 9.5f, 3, paint); break;
            case CHEVRON:
                path.moveTo(9, 5.5f); path.lineTo(15.5f, 12); path.lineTo(9, 18.5f); canvas.drawPath(path, paint); break;
            case MORE:
                dot(canvas, 5.5f, 12, 1.6f); dot(canvas, 12, 12, 1.6f); dot(canvas, 18.5f, 12, 1.6f); break;
            case IMPORT:
                line(canvas, 12, 3.5f, 12, 14.5f); path.moveTo(7.5f, 10); path.lineTo(12, 14.5f); path.lineTo(16.5f, 10); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(4, 15.5f); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15.5f); canvas.drawPath(path, paint); break;
            case EXPORT:
                line(canvas, 12, 14.5f, 12, 3.5f); path.moveTo(7.5f, 8); path.lineTo(12, 3.5f); path.lineTo(16.5f, 8); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(4, 15.5f); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15.5f); canvas.drawPath(path, paint); break;
            case COPY:
                round(canvas, 8.5f, 8.5f, 20, 20, 2.5f);
                path.moveTo(15.5f, 5.5f); path.lineTo(15.5f, 4.5f); path.quadTo(15.5f, 4, 15, 4); path.lineTo(5, 4); path.quadTo(4, 4, 4, 5); path.lineTo(4, 15); path.quadTo(4, 15.5f, 4.5f, 15.5f); path.lineTo(5.5f, 15.5f);
                canvas.drawPath(path, paint); break;
            case TRASH:
                line(canvas, 4, 7, 20, 7); path.moveTo(9.5f, 7); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(14.5f, 7); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(6, 7); path.lineTo(7, 20); path.lineTo(17, 20); path.lineTo(18, 7); canvas.drawPath(path, paint);
                line(canvas, 10, 11, 10, 16.5f); line(canvas, 14, 11, 14, 16.5f); break;
            case CHECK:
                path.moveTo(5, 12.5f); path.lineTo(10, 17.5f); path.lineTo(19.5f, 7); canvas.drawPath(path, paint); break;
            case CLOSE:
                line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); break;
            case SHIELD:
                path.moveTo(12, 3); path.lineTo(19.5f, 6); path.lineTo(19.5f, 11.5f); path.cubicTo(19.5f, 16, 16.2f, 19.5f, 12, 21);
                path.cubicTo(7.8f, 19.5f, 4.5f, 16, 4.5f, 11.5f); path.lineTo(4.5f, 6); path.close(); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(8.5f, 12); path.lineTo(11, 14.5f); path.lineTo(15.5f, 9.5f); canvas.drawPath(path, paint); break;
            case BELL:
                path.moveTo(6, 16.5f); path.lineTo(6, 11); path.cubicTo(6, 7.5f, 8.7f, 5, 12, 5); path.cubicTo(15.3f, 5, 18, 7.5f, 18, 11); path.lineTo(18, 16.5f);
                path.lineTo(19.5f, 18); path.lineTo(4.5f, 18); path.close(); canvas.drawPath(path, paint); line(canvas, 10.5f, 21, 13.5f, 21); line(canvas, 12, 3, 12, 5); break;
            case WINDOW:
                round(canvas, 3, 4.5f, 21, 19.5f, 3); round(canvas, 11.5f, 11, 18, 16.5f, 1.5f); break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6, paint); line(canvas, 15, 15, 20, 20); break;
            case INFO:
                canvas.drawCircle(12, 12, 9, paint); line(canvas, 12, 11, 12, 16.5f); dot(canvas, 12, 7.8f, 1.1f); break;
            case EYE:
                path.moveTo(2.5f, 12); path.cubicTo(5, 7, 8.5f, 5, 12, 5); path.cubicTo(15.5f, 5, 19, 7, 21.5f, 12);
                path.cubicTo(19, 17, 15.5f, 19, 12, 19); path.cubicTo(8.5f, 19, 5, 17, 2.5f, 12); path.close(); canvas.drawPath(path, paint);
                canvas.drawCircle(12, 12, 3, paint); break;
            case HOME:
                path.moveTo(4, 10.5f); path.lineTo(12, 4); path.lineTo(20, 10.5f); path.lineTo(20, 20); path.lineTo(4, 20); path.close(); canvas.drawPath(path, paint);
                break;
            default: break;
        }
        canvas.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    private void round(Canvas c, float l, float t, float r, float b, float radius) { oval.set(l, t, r, b); c.drawRoundRect(oval, radius, radius, paint); }
    private void dot(Canvas c, float x, float y, float r) {
        paint.setStyle(Paint.Style.FILL); c.drawCircle(x, y, r, paint); paint.setStyle(Paint.Style.STROKE);
    }
    private void fill(Canvas c) {
        paint.setStyle(Paint.Style.FILL); c.drawPath(path, paint); paint.setStyle(Paint.Style.STROKE); c.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
