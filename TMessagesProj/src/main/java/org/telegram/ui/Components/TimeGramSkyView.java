package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.Calendar;
import java.util.Random;

/**
 * TimeGram login background: a sky gradient that follows the time of day and fades into
 * the theme background, with a few stars at night. Redraws once a minute.
 */
public class TimeGramSkyView extends View {

    private static final int STAR_COUNT = 26;

    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** x, y (fractions of the view size), radius in dp, brightness */
    private final float[][] stars = new float[STAR_COUNT][4];

    private int shaderTop, shaderMiddle, shaderBottom, shaderHeight;

    public TimeGramSkyView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final Random random = new Random(7); // fixed seed: stars don't jump between redraws
        for (int i = 0; i < STAR_COUNT; i++) {
            stars[i][0] = (i + random.nextFloat()) / STAR_COUNT; // spread over the width
            stars[i][1] = 0.02f + 0.36f * random.nextFloat();
            stars[i][2] = 0.7f + 0.9f * random.nextFloat();
            stars[i][3] = 0.45f + 0.55f * random.nextFloat();
        }
    }

    /** Whether the current app theme has a dark background. */
    public static boolean isDarkTheme() {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000) < 0.5;
    }

    public static TimeGramSky.Colors currentColors() {
        final Calendar c = Calendar.getInstance();
        return TimeGramSky.get(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), isDarkTheme());
    }

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleTick();
        }
    };

    private void scheduleTick() {
        removeCallbacks(minuteTick);
        postDelayed(minuteTick, 60_000 - (System.currentTimeMillis() % 60_000));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
        scheduleTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(minuteTick);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            invalidate();
            scheduleTick();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final boolean dark = isDarkTheme();
        final TimeGramSky.Colors colors = currentColors();
        final int bottom = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000;

        if (skyPaint.getShader() == null || shaderTop != colors.top || shaderMiddle != colors.middle || shaderBottom != bottom || shaderHeight != h) {
            shaderTop = colors.top;
            shaderMiddle = colors.middle;
            shaderBottom = bottom;
            shaderHeight = h;
            skyPaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{colors.top, colors.middle, bottom, bottom},
                    new float[]{0f, 0.32f, 0.65f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, skyPaint);

        if (colors.stars > 0f) {
            final int starColor = dark ? 0xFFFFFFFF : 0xFF5660A8;
            for (float[] star : stars) {
                starPaint.setColor(starColor);
                starPaint.setAlpha((int) (200 * star[3] * colors.stars));
                canvas.drawCircle(star[0] * w, star[1] * h, AndroidUtilities.dpf2(star[2]), starPaint);
            }
        }
    }
}
