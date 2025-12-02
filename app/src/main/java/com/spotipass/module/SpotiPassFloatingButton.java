package com.spotipass.module;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class SpotiPassFloatingButton {

    private static final String TAG = "spotipass_fab";
    private static final int SIZE_DP = 48;
    private static final int MARGIN_DP = 16;
    private static final int ICON_PADDING_DP = 10;
    private static final float ALPHA = 0.7f;
    private static final int DRAG_THRESHOLD_PX = 10;

    private static volatile int savedX = -1;
    private static volatile int savedY = -1;
    private SpotiPassFloatingButton() {}

    static void inject(Activity activity, Runnable onClickAction) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.getWindow().getDecorView().post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                View decorView = activity.getWindow().getDecorView();
                if (!(decorView instanceof ViewGroup)) return;

                View existing = decorView.findViewWithTag(TAG);
                if (existing != null) {
                    return;
                }

                ViewGroup container = (ViewGroup) decorView;
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                float density = dm.density;
                int sizePx = (int) (SIZE_DP * density);
                int marginPx = (int) (MARGIN_DP * density);
                int iconPaddingPx = (int) (ICON_PADDING_DP * density);

                ImageView fab = new ImageView(activity);
                fab.setTag(TAG);
                fab.setImageDrawable(new SpotiPassIcon());
                fab.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx);
                fab.setAlpha(ALPHA);
                fab.setScaleType(ImageView.ScaleType.FIT_CENTER);

                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(Color.argb(180, 255, 255, 255));
                fab.setBackground(bg);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);

                int screenW = dm.widthPixels;
                int screenH = dm.heightPixels;
                int defaultX = screenW - sizePx - marginPx;
                int defaultY = screenH - sizePx - marginPx - (int) (64 * density);

                int x = savedX >= 0 ? savedX : defaultX;
                int y = savedY >= 0 ? savedY : defaultY;

                x = Math.max(0, Math.min(x, screenW - sizePx));
                y = Math.max(0, Math.min(y, screenH - sizePx));

                lp.leftMargin = x;
                lp.topMargin = y;

                fab.setOnTouchListener(new DragTouchListener(fab, screenW, screenH, sizePx));
                fab.setOnClickListener(v -> {
                    if (onClickAction != null) onClickAction.run();
                });

                container.addView(fab, lp);
            } catch (Throwable ignored) {
            }
        });
    }

    private static final class DragTouchListener implements View.OnTouchListener {

        private final View view;
        private final int screenW;
        private final int screenH;
        private final int viewSize;

        private float downRawX, downRawY;
        private int downMarginLeft, downMarginTop;
        private boolean dragging;

        DragTouchListener(View view, int screenW, int screenH, int viewSize) {
            this.view = view;
            this.screenW = screenW;
            this.screenH = screenH;
            this.viewSize = viewSize;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downMarginLeft = lp.leftMargin;
                    downMarginTop = lp.topMargin;
                    dragging = false;
                    return false;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX)) {
                        dragging = true;
                        // Cancel click/longclick once drag starts
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        int newX = (int) (downMarginLeft + dx);
                        int newY = (int) (downMarginTop + dy);
                        newX = Math.max(0, Math.min(newX, screenW - viewSize));
                        newY = Math.max(0, Math.min(newY, screenH - viewSize));
                        lp.leftMargin = newX;
                        lp.topMargin = newY;
                        view.setLayoutParams(lp);
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        savedX = lp.leftMargin;
                        savedY = lp.topMargin;
                        return true;
                    }
                    return false;
            }
            return false;
        }
    }
}
