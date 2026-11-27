package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import android.view.ViewConfiguration;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Calendar;

/**
 * TimeGram intro clock.
 * On attach: ticks pop in one by one, the hands spin fast and settle on the real current time
 * (with a small overshoot), then the second hand keeps ticking. Tap to replay.
 */
public class TimeGramClockView extends View {

    private static final long INTRO_DURATION = 1800;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator introAnimator;
    private float intro = 0f; // 0..1
    private boolean running;

    // busy spin (while a request is in flight) and per-digit pulse
    private ValueAnimator busyAnimator;
    private float busyAngle; // degrees, applied to all hands (multiples of 360 return to rest)
    private ValueAnimator pulseAnimator;
    private float pulse = 1f; // 0..1, 1 = idle
    private float kick;       // second-hand kick in degrees

    // "wrong number" shake: 0..1 while running, -1 when idle
    public static final long ERROR_DURATION = 1000;
    private ValueAnimator errorAnimator;
    private float errorTime = -1f;

    /** How red the hands are at moment t (0..1) of the error shake: quick in, hold, fade out. */
    static float errorRedness(float t) {
        if (t < 0f) {
            return 0f;
        }
        return t < 0.1f ? t / 0.1f : t > 0.7f ? Math.max(0f, (1f - t) / 0.3f) : 1f;
    }

    /** Extra hand angle in degrees: a fast tremble that dies out, so the hand ends up where it was. */
    static float errorJitter(float t, int hand) {
        if (t < 0f) {
            return 0f;
        }
        final float fade = 1f - t;
        return 14f * fade * fade * (float) Math.sin(t * Math.PI * 2 * (9 + hand * 2));
    }

    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.1f);

    public TimeGramClockView(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        handPaint.setStyle(Paint.Style.STROKE);
        handPaint.setStrokeCap(Paint.Cap.ROUND);
        secondPaint.setStyle(Paint.Style.STROKE);
        secondPaint.setStrokeCap(Paint.Cap.ROUND);
        centerPaint.setStyle(Paint.Style.FILL);
        setContentDescription("TimeGram");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        playIntro();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        if (introAnimator != null) {
            introAnimator.cancel();
            introAnimator = null;
        }
        removeCallbacks(tick);
        removeCallbacks(longPress);
        if (busyAnimator != null) {
            busyAnimator.cancel();
            busyAnimator = null;
            busyAngle = 0f;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (errorAnimator != null) {
            errorAnimator.cancel();
            errorAnimator = null;
        }
        errorTime = -1f;
    }

    /** Wrong phone number: the hands tremble and turn red for a second. */
    public void shakeError() {
        if (errorAnimator != null) {
            errorAnimator.cancel();
        }
        errorAnimator = ValueAnimator.ofFloat(0f, 1f);
        errorAnimator.setDuration(ERROR_DURATION);
        errorAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        // if the hands are still spinning for the request, let them stop first
        errorAnimator.setStartDelay(busyAnimator != null ? 500 : 0);
        errorAnimator.addUpdateListener(a -> {
            errorTime = (float) a.getAnimatedValue();
            invalidate();
        });
        errorAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                errorTime = -1f;
                invalidate();
            }
        });
        errorAnimator.start();
    }

    public void playIntro() {
        if (introAnimator != null) {
            introAnimator.cancel();
        }
        removeCallbacks(tick);
        intro = 0f;
        introAnimator = ValueAnimator.ofFloat(0f, 1f);
        introAnimator.setDuration(INTRO_DURATION);
        introAnimator.addUpdateListener(a -> {
            intro = (float) a.getAnimatedValue();
            invalidate();
        });
        introAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (introAnimator == animation) {
                    intro = 1f;
                    introAnimator = null;
                    invalidate();
                    scheduleTick();
                }
            }
        });
        introAnimator.start();
    }


    /** Short ripple + second-hand kick. Call on every typed digit. */
    public void pulse() {
        if (isHapticEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(450);
        pulseAnimator.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            kick = 14f * (1f - pulse);
            invalidate();
        });
        pulseAnimator.start();
    }

    /** Hands spin continuously while busy, then settle back on the real time. */
    public void setBusy(boolean busy) {
        if (busy) {
            if (busyAnimator != null && busyAnimator.isRunning() && busyAnimator.getRepeatCount() == ValueAnimator.INFINITE) {
                return;
            }
            if (busyAnimator != null) {
                busyAnimator.cancel();
            }
            busyAnimator = ValueAnimator.ofFloat(0f, 360f);
            busyAnimator.setDuration(900);
            busyAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
            busyAnimator.setRepeatCount(ValueAnimator.INFINITE);
            busyAnimator.addUpdateListener(a -> {
                busyAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            busyAnimator.start();
        } else {
            if (busyAnimator == null) {
                return;
            }
            float from = busyAngle;
            busyAnimator.cancel();
            busyAnimator = ValueAnimator.ofFloat(from, 360f);
            busyAnimator.setDuration(500);
            busyAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            busyAnimator.addUpdateListener(a -> {
                busyAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            busyAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (busyAnimator == animation) {
                        busyAngle = 0f;
                        busyAnimator = null;
                        invalidate();
                    }
                }
            });
            busyAnimator.start();
        }
    }


    private static final String PREF_HAPTIC = "timegram_login_haptic";

    /** Haptic tick on typed digits. Also respects the system "touch feedback" setting. */
    public static boolean isHapticEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_HAPTIC, true);
    }

    private boolean longPressed;
    private final Runnable longPress = () -> {
        longPressed = true;
        boolean enabled = !isHapticEnabled();
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_HAPTIC, enabled).apply();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Toast.makeText(getContext(), LocaleController.getString(enabled ? R.string.TimeGramHapticOn : R.string.TimeGramHapticOff), Toast.LENGTH_SHORT).show();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            scheduleTick();
        }
    };

    private void scheduleTick() {
        removeCallbacks(tick);
        postDelayed(tick, 1000 - (System.currentTimeMillis() % 1000));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                longPressed = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!longPressed) {
                    playIntro();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = AndroidUtilities.dp(112);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float radius = Math.min(w, h) / 2f - AndroidUtilities.dp(4);

        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = TimeGramSkyView.currentColors().accent;

        // soft dial so the hands stay readable on any sky
        float faceT = clamp01(intro / 0.3f);
        centerPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        centerPaint.setAlpha((int) (150 * faceT));
        canvas.drawCircle(cx, cy, radius * easeOut(faceT), centerPaint);

        // ring grows in during the first 30% of the animation
        float ringT = clamp01(intro / 0.3f);
        ringPaint.setColor(accent);
        ringPaint.setStrokeWidth(AndroidUtilities.dp(3.5f));
        canvas.drawCircle(cx, cy, radius * easeOut(ringT), ringPaint);

        if (pulse < 1f) {
            ringPaint.setAlpha((int) (140 * (1f - pulse)));
            ringPaint.setStrokeWidth(AndroidUtilities.dp(2f));
            canvas.drawCircle(cx, cy, radius * (0.95f + 0.3f * easeOut(pulse)), ringPaint);
            ringPaint.setAlpha(255);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(3.5f));
        }

        // 12 ticks pop in one after another between 10% and 70%
        tickPaint.setColor(textColor);
        for (int i = 0; i < 12; i++) {
            float start = 0.1f + 0.5f * (i / 12f);
            float t = clamp01((intro - start) / 0.12f);
            if (t <= 0f) {
                continue;
            }
            boolean major = i % 3 == 0;
            tickPaint.setStrokeWidth(AndroidUtilities.dp(major ? 3f : 2f));
            float len = AndroidUtilities.dp(major ? 9 : 5) * easeOut(t);
            double ang = Math.toRadians(i * 30 - 90);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            float outer = radius - AndroidUtilities.dp(7);
            canvas.drawLine(cx + cos * outer, cy + sin * outer, cx + cos * (outer - len), cy + sin * (outer - len), tickPaint);
        }

        // real time
        Calendar c = Calendar.getInstance();
        float sec = c.get(Calendar.SECOND);
        float min = c.get(Calendar.MINUTE) + sec / 60f;
        float hour = (c.get(Calendar.HOUR) % 12) + min / 60f;

        float handsT = clamp01((intro - 0.25f) / 0.75f);
        float k = overshoot.getInterpolation(handsT);
        float spinsLeft = 1f - k; // extra full turns that unwind to 0

        float hourAngle = hour * 30f - 360f * 1f * spinsLeft + busyAngle;
        float minAngle = min * 6f - 360f * 3f * spinsLeft + busyAngle * 3f;
        float secAngle = sec * 6f - 360f * 5f * spinsLeft + busyAngle * 6f + kick;

        float handsAlpha = clamp01((intro - 0.25f) / 0.15f);

        final float redness = errorRedness(errorTime);
        final int errorColor = Theme.getColor(Theme.key_text_RedBold);
        hourAngle += errorJitter(errorTime, 0);
        minAngle += errorJitter(errorTime, 1);
        secAngle += errorJitter(errorTime, 2);

        handPaint.setColor(ColorUtils.blendARGB(textColor, errorColor, redness));
        handPaint.setAlpha((int) (255 * handsAlpha));
        handPaint.setStrokeWidth(AndroidUtilities.dp(4.5f));
        drawHand(canvas, cx, cy, hourAngle, radius * 0.45f, handPaint);

        handPaint.setStrokeWidth(AndroidUtilities.dp(3.5f));
        drawHand(canvas, cx, cy, minAngle, radius * 0.68f, handPaint);

        final int pointerColor = ColorUtils.blendARGB(accent, errorColor, redness);
        secondPaint.setColor(pointerColor);
        secondPaint.setAlpha((int) (255 * handsAlpha));
        secondPaint.setStrokeWidth(AndroidUtilities.dp(2f));
        drawHand(canvas, cx, cy, secAngle, radius * 0.76f, secondPaint);

        centerPaint.setColor(pointerColor);
        centerPaint.setAlpha((int) (255 * handsAlpha));
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(4.5f), centerPaint);
    }

    private static void drawHand(Canvas canvas, float cx, float cy, float angleDeg, float length, Paint paint) {
        double ang = Math.toRadians(angleDeg - 90f);
        canvas.drawLine(cx, cy, cx + (float) Math.cos(ang) * length, cy + (float) Math.sin(ang) * length, paint);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }
}
