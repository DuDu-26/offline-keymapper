package cn.offline.mapper;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Styled dialog surface: a title, any content, and optional action buttons. */
public final class Sheet {
    public interface Item { void run() throws Exception; }
    public interface Named { void accept(String name) throws Exception; }

    public final AlertDialog dialog;
    public final LinearLayout body, actions;
    private final Context context;
    private final TextView error;

    public Sheet(Context base, String title, String subtitle, boolean overlay) {
        context = new android.view.ContextThemeWrapper(base, android.R.style.Theme_Material_Dialog);
        LinearLayout root = Ui.column(context);
        root.setBackground(Ui.shape(context, Ui.SURFACE, 24, Ui.STROKE));
        root.setPadding(Ui.dp(context, 20), Ui.dp(context, 20), Ui.dp(context, 20), Ui.dp(context, 16));
        if (title != null) root.addView(Ui.title(context, title, 19));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(context, subtitle, 13, Ui.MUTED);
            root.addView(s, Ui.margins(context, Ui.wrap(), 0, 4, 0, 0));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        body = Ui.column(context);
        scroll.addView(body);
        root.addView(scroll, Ui.margins(context, new LinearLayout.LayoutParams(-1, -2, 1f), 0, 14, 0, 0));
        error = Ui.text(context, "", 13, Ui.DANGER);
        error.setVisibility(View.GONE);
        root.addView(error, Ui.margins(context, Ui.wrap(), 0, 8, 0, 0));
        actions = Ui.row(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(actions, Ui.margins(context, Ui.fill(-2), 0, 12, 0, 0));
        dialog = new AlertDialog.Builder(context).setView(root).create();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            if (overlay) w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    public Context context() { return context; }

    public void showError(String message) {
        error.setText(message == null ? "操作失败" : message);
        error.setVisibility(View.VISIBLE);
    }

    /** Adds a button to the action bar. {@code action} may throw to keep the sheet open with an error. */
    public Sheet action(String label, Ui.Kind kind, Item action) {
        LinearLayout b = Ui.button(context, label, 0, kind, () -> {
            try { if (action != null) action.run(); dialog.dismiss(); }
            catch (Exception problem) { showError(problem.getMessage()); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(context, 44));
        lp.leftMargin = Ui.dp(context, 8);
        actions.addView(b, lp);
        return this;
    }

    /** Adds a tappable menu row that closes the sheet. */
    public Sheet item(int icon, int color, String title, String subtitle, Item action) {
        body.addView(Ui.listRow(context, icon, color, title, subtitle, null, () -> {
            dialog.dismiss();
            try { action.run(); } catch (Exception problem) { android.widget.Toast.makeText(context, problem.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
        }));
        return this;
    }

    public AlertDialog show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            android.util.DisplayMetrics m = context.getResources().getDisplayMetrics();
            w.setLayout(Math.min(Ui.dp(context, 440), m.widthPixels - Ui.dp(context, 24)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /** Text input sheet with validation for 1–80 character names. */
    public static void name(Context base, String title, String value, Named accept) {
        Sheet sheet = new Sheet(base, title, null, false);
        EditText input = field(sheet.context, value, "名称");
        sheet.body.addView(input, Ui.fill(Ui.dp(sheet.context, 52)));
        sheet.action("取消", Ui.Kind.GHOST, null).action("保存", Ui.Kind.PRIMARY, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("请输入 1～80 字的名称");
            accept.accept(name);
        });
        sheet.show();
        input.requestFocus();
    }

    public static EditText field(Context c, String value, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(true); e.setText(value); e.setHint(hint);
        e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.FAINT); e.setTextSize(15);
        e.setBackground(Ui.shape(c, Ui.SURFACE_2, 12, Ui.STROKE));
        e.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        return e;
    }

    /** Confirmation sheet with a destructive action. */
    public static void confirm(Context base, String title, String message, String confirm, Item action) {
        Sheet sheet = new Sheet(base, title, message, false);
        sheet.action("取消", Ui.Kind.GHOST, null).action(confirm, Ui.Kind.DANGER, action);
        sheet.show();
    }
}
