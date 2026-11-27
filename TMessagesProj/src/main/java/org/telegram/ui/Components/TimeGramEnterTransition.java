package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

/**
 * TimeGram "login to chat list" transition: the clock grows and dissolves into the app.
 * <p>
 * It does not touch Telegram's fragment transitions. A temporary layer with a snapshot of the
 * login screen is put over the whole window, the app switches screens underneath as usual,
 * and then the layer opens up from the clock. If anything fails, the login goes on without it.
 */
@SuppressLint("ViewConstructor")
public class TimeGramEnterTransition extends View {

    private static final long START_DELAY = 150; // lets the chat list lay out under the layer
    private static final long DURATION = 900;
    private static final float CLOCK_SCALE = 5f;
    private static final int CLOCK_RESOLUTION = 3; // the clock bitmap is drawn bigger to stay sharp while growing

    private final Bitmap screen;
    private final Bitmap clock;
    /** where the login screen and the clock were, in window coordinates */
    private final float screenX, screenY;
    private final float clockX, clockY, clockWidth, clockHeight;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private ValueAnimator animator;
    private float progress;

    private final Runnable removeTask = this::remove;

    private TimeGramEnterTransition(Context context, Bitmap screen, int[] screenLocation, Bitmap clock, float clockX, float clockY, float clockWidth, float clockHeight) {
        super(context);
        this.screen = screen;
        this.screenX = screenLocation[0];
        this.screenY = screenLocation[1];
        this.clock = clock;
        this.clockX = clockX;
        this.clockY = clockY;
        this.clockWidth = clockWidth;
        this.clockHeight = clockHeight;
        holePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    /**
     * Call right before the login screen is replaced.
     *
     * @param screenView  the whole login screen
     * @param clockView   a laid out clock
     * @param clockOnScreen true if the user sees this clock now: it grows from its place;
     *                    otherwise (password, sign up) it appears in the middle of the screen
     */
    public static void play(Activity activity, View screenView, View clockView, boolean clockOnScreen) {
        if (activity == null || screenView == null || clockView == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        try {
            final int width = screenView.getWidth(), height = screenView.getHeight();
            final int clockWidth = clockView.getWidth(), clockHeight = clockView.getHeight();
            if (width <= 0 || height <= 0 || clockWidth <= 0 || clockHeight <= 0) {
                return;
            }
            final ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            final int[] screenLocation = new int[2];
            screenView.getLocationInWindow(screenLocation);

            final float x, y;
            if (clockOnScreen) {
                final int[] location = new int[2];
                clockView.getLocationInWindow(location);
                x = location[0] + clockWidth / 2f;
                y = location[1] + clockHeight / 2f;
            } else {
                x = screenLocation[0] + width / 2f;
                y = screenLocation[1] + height * 0.4f;
            }

            final Bitmap clockBitmap = Bitmap.createBitmap(clockWidth * CLOCK_RESOLUTION, clockHeight * CLOCK_RESOLUTION, Bitmap.Config.ARGB_8888);
            final Canvas clockCanvas = new Canvas(clockBitmap);
            clockCanvas.scale(CLOCK_RESOLUTION, CLOCK_RESOLUTION);
            clockView.draw(clockCanvas);

            // the screen is captured without the clock, so there is only one clock while it grows
            final Bitmap screenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas screenCanvas = new Canvas(screenBitmap);
            screenCanvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000);
            final int visibility = clockView.getVisibility();
            clockView.setVisibility(INVISIBLE);
            try {
                screenView.draw(screenCanvas);
            } finally {
                clockView.setVisibility(visibility);
            }

            final TimeGramEnterTransition transition = new TimeGramEnterTransition(activity, screenBitmap, screenLocation, clockBitmap, x, y, clockWidth, clockHeight);
            root.addView(transition, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            transition.start();
        } catch (Throwable e) { // including OutOfMemoryError: the transition is only decoration
            FileLog.e(e);
        }
    }

    private void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setStartDelay(START_DELAY);
        animator.setDuration(DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                post(removeTask);
            }
        });
        animator.start();
        // safety net: the layer must never stay over the app
        postDelayed(removeTask, START_DELAY + DURATION + 1000);
    }

    private void remove() {
        removeCallbacks(removeTask);
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true; // no accidental taps on chats that are not visible yet
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int[] location = new int[2];
        getLocationInWindow(location);
        canvas.translate(-location[0], -location[1]);

        final float grow = smooth(progress);
        // the clock drifts to the middle while it grows
        final float centerX = lerp(clockX, screenX + screen.getWidth() / 2f, grow);
        final float centerY = lerp(clockY, screenY + screen.getHeight() / 2f, grow);

        // login screen: fades out and opens up from the clock
        final float dissolve = smooth((progress - 0.2f) / 0.75f);
        if (dissolve < 1f) {
            final int alpha = (int) (255 * (1f - dissolve));
            rect.set(screenX, screenY, screenX + screen.getWidth(), screenY + screen.getHeight());
            final int save = canvas.saveLayerAlpha(rect, alpha);
            canvas.drawBitmap(screen, screenX, screenY, null);
            if (dissolve > 0f) {
                final float radius = (float) Math.hypot(screen.getWidth(), screen.getHeight()) * dissolve + 1f;
                holePaint.setShader(new RadialGradient(centerX, centerY, radius, new int[]{0xFF000000, 0xFF000000, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(centerX, centerY, radius, holePaint);
            }
            canvas.restoreToCount(save);
        }

        final float clockAlpha = 1f - smooth((progress - 0.3f) / 0.6f);
        if (clockAlpha > 0f) {
            final float scale = lerp(1f, CLOCK_SCALE, grow);
            final float halfWidth = clockWidth * scale / 2f, halfHeight = clockHeight * scale / 2f;
            rect.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
            bitmapPaint.setAlpha((int) (255 * clockAlpha));
            canvas.drawBitmap(clock, null, rect, bitmapPaint);
        }
    }

    private static float smooth(float t) {
        t = t < 0f ? 0f : Math.min(t, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
