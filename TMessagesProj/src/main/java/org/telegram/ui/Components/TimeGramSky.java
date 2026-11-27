package org.telegram.ui.Components;

/**
 * TimeGram sky palette: colors behind the login form for any minute of the day.
 * Plain Java (no Android classes). Light and dark app themes have their own palettes,
 * so that theme text colors stay readable on top of the sky.
 */
public final class TimeGramSky {

    public static final class Colors {
        public int top;
        public int middle;
        /** clock ring and second hand */
        public int accent;
        /** 0..1 */
        public float stars;
    }

    private static final int NIGHT = 0, DAWN = 1, DAY = 2, SUNSET = 3;

    // {top, middle, accent}
    private static final int[][] LIGHT = {
            {0xFFC3C9EE, 0xFFE4E6F8, 0xFF4551B0}, // night
            {0xFFFFC9B5, 0xFFFFEBD9, 0xFFB4432C}, // dawn
            {0xFFB3DCFF, 0xFFE0F1FF, 0xFF1569B5}, // day
            {0xFFFFBE9E, 0xFFF9E0EC, 0xFFA83266}, // sunset
    };
    private static final int[][] DARK = {
            {0xFF0A0F2E, 0xFF111838, 0xFF93A5FF}, // night
            {0xFF4A2747, 0xFF33263B, 0xFFFFA284}, // dawn
            {0xFF153F66, 0xFF17304B, 0xFF62C2FF}, // day
            {0xFF582440, 0xFF372242, 0xFFFF9470}, // sunset
    };

    // minute of day -> phase; equal neighbours hold the color, different ones blend
    private static final int[] MINUTES = {0, 270, 390, 570, 990, 1140, 1290, 1440};
    private static final int[] PHASES = {NIGHT, NIGHT, DAWN, DAY, DAY, SUNSET, NIGHT, NIGHT};

    private TimeGramSky() {
    }

    public static Colors get(int minuteOfDay, boolean dark) {
        final int minute = ((minuteOfDay % 1440) + 1440) % 1440;
        int i = 0;
        while (minute >= MINUTES[i + 1]) {
            i++;
        }
        float t = (minute - MINUTES[i]) / (float) (MINUTES[i + 1] - MINUTES[i]);
        t = t * t * (3f - 2f * t); // smoothstep

        final int[][] palette = dark ? DARK : LIGHT;
        final int[] from = palette[PHASES[i]];
        final int[] to = palette[PHASES[i + 1]];

        final Colors result = new Colors();
        result.top = blend(from[0], to[0], t);
        result.middle = blend(from[1], to[1], t);
        result.accent = blend(from[2], to[2], t);
        final float starsFrom = PHASES[i] == NIGHT ? 1f : 0f;
        final float starsTo = PHASES[i + 1] == NIGHT ? 1f : 0f;
        result.stars = starsFrom + (starsTo - starsFrom) * t;
        return result;
    }

    private static int blend(int a, int b, float t) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            final int ca = (a >>> shift) & 0xFF;
            final int cb = (b >>> shift) & 0xFF;
            result |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return result;
    }
}
