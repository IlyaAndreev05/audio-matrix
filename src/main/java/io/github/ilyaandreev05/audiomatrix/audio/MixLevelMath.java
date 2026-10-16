package io.github.ilyaandreev05.audiomatrix.audio;

/** Shared level math for the effects master, bus and route stages. */
public final class MixLevelMath {
    private static final double MAX_ROUTE_GAIN_DB = 24.0;

    private MixLevelMath() {}

    public static double effectiveBusVolume(double busVolume, double masterVolume) {
        return clamp(busVolume, 0, 1) * clamp(masterVolume, 0, 1);
    }

    public static boolean effectiveBusMute(boolean busMuted, boolean masterMuted) {
        return busMuted || masterMuted;
    }

    public static double effectiveRouteVolume(double routeVolume, double gainDb) {
        double safeGain = clamp(gainDb, -60, MAX_ROUTE_GAIN_DB);
        return clamp(routeVolume, 0, 1) * Math.pow(10.0, safeGain / 20.0);
    }

    public static double maxRouteVolume() { return Math.pow(10.0, MAX_ROUTE_GAIN_DB / 20.0); }

    public static double toDb(double linear) {
        return linear <= 0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(linear);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }
}
