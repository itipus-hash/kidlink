package com.kidlink.beacon.ui.widget;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;

import java.util.function.Predicate;

/**
 * 一个「输入名字」的对话框，三处共用：信标改名、娃改名、新建娃，
 * 以及扫完信标顺手让用户起名。
 *
 * 三条行为是刻意的：
 * 1. **输入框空着打开** —— 改名是重写，不是接着往后打。预填旧名字的话，
 *    用户得先全选删掉，多一步还容易改成「书包书包」。
 * 2. **打开就聚焦、键盘直接顶起来** —— 点进来就是为了打字，不用再点一下输入框。
 * 3. **原来叫什么写在 hint 里**（「当前：书包」）—— 清空之后一时想不起，
 *    一眼能看到。
 *
 * 不点保存（取消 / 点弹窗外）什么都不写，名字自然还是原来那个；
 * 空着点保存只提示一句、弹窗不关，等同「没有改动」。
 */
public final class NameDialog {

    private NameDialog() {
    }

    /**
     * @param saver 返回 true 表示已保存（弹窗关闭），false 表示没成功（弹窗留着，让用户改）
     */
    public static void show(Activity activity, CharSequence title, CharSequence hint,
                            Predicate<String> saver) {
        show(activity, title, hint, saver, null, true);
    }

    public static void show(Activity activity, CharSequence title, CharSequence hint,
                            Predicate<String> saver, Runnable onDismiss) {
        show(activity, title, hint, saver, onDismiss, true);
    }

    /**
     * @param onDismiss 弹窗关掉之后做什么（保存和点外面都算）。扫完信标那一处用它退出页面：
     *                  信标已经存下来了，不改名也不该把人留在扫描页再扫一遍。
     * @param showCancel 是否给「取消」。扫完信标那一处不给：这颗信标已经存进来了，
     *                   摆一个「取消」容易让人以为取消就等于不保存。想跳过就直接点外面。
     */
    public static void show(Activity activity, CharSequence title, CharSequence hint,
                            Predicate<String> saver, Runnable onDismiss, boolean showCancel) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        // 空串 = 不显示任何占位提示，输入框干干净净
        if (hint != null && hint.length() > 0) {
            input.setHint(hint);
            input.setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        }
        input.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(wrapInPadding(activity, input))
                .setPositiveButton(R.string.save, null);
        if (showCancel) builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();

        dialog.setOnShowListener(d -> {
            // 保存按钮接管点击：空名字就不关弹窗，否则用户看到的只是「闪一下就没了」
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    Feedback.show(activity.findViewById(android.R.id.content),
                            activity.getString(R.string.rename_empty));
                    return;
                }
                if (saver.test(name)) dialog.dismiss();
            });
            input.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        dialog.show();
    }

    private static View wrapInPadding(Activity activity, View child) {
        FrameLayout container = new FrameLayout(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int h = (int) (20 * density);
        int v = (int) (8 * density);
        container.setPadding(h, v, h, 0);
        container.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }
}
