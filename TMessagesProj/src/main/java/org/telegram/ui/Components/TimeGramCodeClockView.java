package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.Calendar;
import java.util.Locale;

/**
 * TimeGram clock on the login code screen.
 * <ul>
 * <li>while a resend countdown runs: the arc around the dial shrinks and the time left is in the middle;</li>
 * <li>otherwise the dial shows the current time;</li>
 * <li>one dial segment per code digit, each lights up when its digit is typed;</li>
 * <li>correct code: the clock strikes twice and flashes expanding rings.</li>
 * </ul>
 */
public class TimeGramCodeClockView extends View {

    public static final long STRIKE_DURATION = 900;

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float[] segments = new float[0]; // 0..1 glow of every segment
    private boolean[] filled = new boolean[0];
    private long lastFrameTime;

    private boolean counting;
    private long countdownEnd;   // SystemClock.elapsedRealtime()
    private long countdownTotal;

    private float strike = -1f;  // 0..1 while striking
    private boolean struck;      // stays green after a correct code
    private float errorTime = -1f; // 0..1 during the wrong code shake
    private float ready = -1f;   // 0..1 ripple when the countdown is over
    private ValueAnimator strikeAnimator, errorAnimator, readyAnimator;

    private final Runnable secondStrike = this::haptic;

    public TimeGramCodeClockView(Context context) {
        super(context);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        segmentPaint.setStyle(Paint.Style.STROKE);
        segmentPaint.setStrokeCap(Paint.Cap.ROUND);
        handPaint.setStyle(Paint.Style.STROKE);
        handPaint.setStrokeCap(Paint.Cap.ROUND);
        flashPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(AndroidUtilities.bold());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the countdown is also shown as text below
    }

    /** New code request: forget the digits, the countdown and the effects. */
    public void reset(int digits) {
        cancelAnimators();
        segments = new float[digits];
        filled = new boolean[digits];
        counting = false;
        struck = false;
        strike = -1f;
        ready = -1f;
        errorTime = -1f;
        invalidate();
    }

    public void setDigit(int index, boolean value) {
        if (index < 0 || index >= filled.length || filled[index] == value) {
            return;
        }
        filled[index] = value;
        if (value && isShown() && TimeGramClockView.isHapticEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    public void setCountdown(long remainingMs, long totalMs) {
        countdownEnd = SystemClock.elapsedRealtime() + remainingMs;
        countdownTotal = Math.max(1, Math.max(totalMs, remainingMs));
        counting = remainingMs > 0;
        invalidate();
    }

    /** While the code is being checked the dial goes back to the current time. */
    public void stopCountdown() {
        counting = false;
        invalidate();
    }

    /** The code can be requested again. */
    public void finishCountdown() {
        counting = false;
        if (readyAnimator != null) {
            readyAnimator.cancel();
        }
        readyAnimator = startAnimator(700, value -> ready = value, () -> ready = -1f);
    }

    /** Wrong code: the hands show up (even during a countdown), tremble and turn red. */
    public void showError() {
        if (errorAnimator != null) {
            errorAnimator.cancel();
        }
        errorAnimator = startAnimator(TimeGramClockView.ERROR_DURATION, value -> errorTime = value, () -> errorTime = -1f);
    }

    /** Correct code. */
    public void strike() {
        cancelAnimators();
        counting = false;
        errorTime = -1f;
        ready = -1f;
        struck = true;
        haptic();
        postDelayed(secondStrike, STRIKE_DURATION / 2);
        strikeAnimator = startAnimator(STRIKE_DURATION, value -> strike = value, () -> strike = -1f);
    }

    private void haptic() {
        if (TimeGramClockView.isHapticEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private interface Update {
        void set(float value);
    }

    private ValueAnimator startAnimator(long duration, Update update, Runnable end) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            update.set((float) a.getAnimatedValue());
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                end.run();
                invalidate();
            }
        });
        animator.start();
        return animator;
    }

    private void cancelAnimators() {
        removeCallbacks(secondStrike);
        if (strikeAnimator != null) {
            strikeAnimator.cancel();
            strikeAnimator = null;
        }
        if (errorAnimator != null) {
            errorAnimator.cancel();
            errorAnimator = null;
        }
        if (readyAnimator != null) {
            readyAnimator.cancel();
            readyAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimators();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // wider than the dial itself to leave room for the flash rings
        int size = AndroidUtilities.dp(136);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = AndroidUtilities.dp(52);

        final long now = SystemClock.elapsedRealtime();
        final float step = Math.min(1f, (now - lastFrameTime) / 180f);
        lastFrameTime = now;

        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int green = Theme.getColor(Theme.key_checkbox);
        int accent = TimeGramSkyView.currentColors().accent;
        final float redness = TimeGramClockView.errorRedness(errorTime);
        final int errorColor = Theme.getColor(Theme.key_text_RedBold);
        accent = ColorUtils.blendARGB(accent, errorColor, redness);
        if (struck) {
            accent = green;
        }

        // two quick "bongs"
        float scale = 1f;
        if (strike >= 0f) {
            final float bong = (strike * 2f) % 1f;
            scale = 1f + 0.12f * (float) Math.sin(Math.PI * Math.min(1f, bong * 2f));
        }
        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        facePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        facePaint.setAlpha(150);
        canvas.drawCircle(cx, cy, radius, facePaint);

        // track + countdown arc
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        arcPaint.setStrokeWidth(AndroidUtilities.dp(3.5f));
        arcPaint.setColor(ColorUtils.setAlphaComponent(accent, counting ? 50 : 255));
        canvas.drawCircle(cx, cy, radius, arcPaint);
        long left = 0;
        if (counting) {
            left = Math.max(0, countdownEnd - now);
            arcPaint.setColor(accent);
            canvas.drawArc(rect, -90, 360f * left / countdownTotal, false, arcPaint);
        }

        // one segment per digit, clockwise from 12 o'clock
        boolean glowing = false;
        final int count = segments.length;
        if (count > 0) {
            final float segmentRadius = radius - AndroidUtilities.dp(9);
            rect.set(cx - segmentRadius, cy - segmentRadius, cx + segmentRadius, cy + segmentRadius);
            final float sweep = 360f / count;
            final float gap = 16f;
            segmentPaint.setStrokeWidth(AndroidUtilities.dp(4.5f));
            for (int i = 0; i < count; i++) {
                final float target = filled[i] || struck ? 1f : 0f;
                if (segments[i] < target) {
                    segments[i] = Math.min(target, segments[i] + step);
                } else if (segments[i] > target) {
                    segments[i] = Math.max(target, segments[i] - step);
                }
                glowing |= segments[i] != target;
                segmentPaint.setColor(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(textColor, 40), accent, segments[i]));
                canvas.drawArc(rect, -90 + i * sweep + gap / 2f, sweep - gap, false, segmentPaint);
            }
        }

        if (counting && errorTime < 0f) {
            final long seconds = left / 1000;
            textPaint.setColor(textColor);
            textPaint.setTextSize(AndroidUtilities.dp(24));
            canvas.drawText(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60), cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
        } else {
            final Calendar c = Calendar.getInstance();
            final float second = c.get(Calendar.SECOND);
            final float minute = c.get(Calendar.MINUTE) + second / 60f;
            final float hour = (c.get(Calendar.HOUR) % 12) + minute / 60f;
            handPaint.setColor(ColorUtils.blendARGB(textColor, errorColor, redness));
            handPaint.setStrokeWidth(AndroidUtilities.dp(4f));
            drawHand(canvas, cx, cy, hour * 30f + TimeGramClockView.errorJitter(errorTime, 0), radius * 0.40f);
            handPaint.setStrokeWidth(AndroidUtilities.dp(3f));
            drawHand(canvas, cx, cy, minute * 6f + TimeGramClockView.errorJitter(errorTime, 1), radius * 0.60f);
            handPaint.setColor(accent);
            handPaint.setStrokeWidth(AndroidUtilities.dp(2f));
            drawHand(canvas, cx, cy, second * 6f + TimeGramClockView.errorJitter(errorTime, 2), radius * 0.66f);
            facePaint.setColor(accent);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(4), facePaint);
        }
        canvas.restore();

        // flash rings
        final float maxGrow = Math.min(getWidth(), getHeight()) / 2f - radius - AndroidUtilities.dp(2);
        if (strike >= 0f) {
            for (int i = 0; i < 2; i++) {
                drawRing(canvas, cx, cy, radius, maxGrow, (strike - i * 0.4f) / 0.6f, green);
            }
        }
        if (ready >= 0f) {
            drawRing(canvas, cx, cy, radius, maxGrow, ready, accent);
        }

        if (counting || glowing) {
            postInvalidateDelayed(16);
        } else {
            postInvalidateDelayed(1000 - (System.currentTimeMillis() % 1000));
        }
    }

    private void drawRing(Canvas canvas, float cx, float cy, float radius, float maxGrow, float t, int color) {
        if (t <= 0f || t >= 1f) {
            return;
        }
        final float eased = 1f - (1f - t) * (1f - t);
        flashPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (220 * (1f - t))));
        flashPaint.setStrokeWidth(AndroidUtilities.dp(5f) * (1f - 0.6f * t));
        canvas.drawCircle(cx, cy, radius + maxGrow * eased, flashPaint);
    }

    private void drawHand(Canvas canvas, float cx, float cy, float angle, float length) {
        final double radians = Math.toRadians(angle - 90f);
        canvas.drawLine(cx, cy, cx + (float) Math.cos(radians) * length, cy + (float) Math.sin(radians) * length, handPaint);
    }
}
