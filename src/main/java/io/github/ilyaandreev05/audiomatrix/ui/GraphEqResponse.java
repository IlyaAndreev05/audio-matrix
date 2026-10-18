package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Calculates the combined parametric EQ response from its biquad coefficients. */
final class GraphEqResponse {
    private GraphEqResponse() {}

    static double responseDb(List<Map<String, Object>> bands, double frequencyHz) {
        double result = 0;
        for (Map<String, Object> band : bands) {
            if (Boolean.FALSE.equals(band.get("enabled"))) continue;
            result += bandResponseDb(band, frequencyHz);
        }
        return result;
    }

    private static double bandResponseDb(Map<String, Object> band, double frequencyHz) {
        double center = number(band.getOrDefault("frequency_hz", band.get("frequencyHz")), 1000);
        double gain = number(band.getOrDefault("gain_db", band.get("gainDb")), 0);
        double q = number(band.get("q"), 1);
        if (!(frequencyHz > 0) || !(center > 0) || !(q > 0)) return 0;
        double omega = 2 * Math.PI * center / 48000.0;
        double sampleOmega = 2 * Math.PI * frequencyHz / 48000.0;
        double cosine = Math.cos(omega), sine = Math.sin(omega);
        double a = Math.pow(10, gain / 40.0), alpha = sine / (2 * q);
        Object rawType = band.containsKey("type") ? band.get("type") : band.containsKey("filter_kind")
                ? band.get("filter_kind") : band.getOrDefault("filterKind", "peaking");
        String type = String.valueOf(rawType).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        Coefficients coefficients = switch (type) {
            case "notch" -> new Coefficients(1, -2 * cosine, 1,
                    1 + alpha, -2 * cosine, 1 - alpha);
            case "low_shelf", "lowshelf" -> shelf(a, cosine, sine, Math.min(1.0, 1.0 / q), true);
            case "high_shelf", "highshelf" -> shelf(a, cosine, sine, Math.min(1.0, 1.0 / q), false);
            default -> new Coefficients(1 + alpha * a, -2 * cosine, 1 - alpha * a,
                    1 + alpha / a, -2 * cosine, 1 - alpha / a);
        };
        return coefficients.magnitudeDb(sampleOmega);
    }

    private static Coefficients shelf(double a, double cosine, double sine, double slope, boolean low) {
        double root = Math.sqrt((a + 1 / a) * (1 / slope - 1) + 2);
        double alpha = sine / 2 * root, beta = 2 * Math.sqrt(a) * alpha;
        if (low) return new Coefficients(a * ((a + 1) - (a - 1) * cosine + beta),
                2 * a * ((a - 1) - (a + 1) * cosine), a * ((a + 1) - (a - 1) * cosine - beta),
                (a + 1) + (a - 1) * cosine + beta, -2 * ((a - 1) + (a + 1) * cosine),
                (a + 1) + (a - 1) * cosine - beta);
        return new Coefficients(a * ((a + 1) + (a - 1) * cosine + beta),
                -2 * a * ((a - 1) + (a + 1) * cosine), a * ((a + 1) + (a - 1) * cosine - beta),
                (a + 1) - (a - 1) * cosine + beta, 2 * ((a - 1) - (a + 1) * cosine),
                (a + 1) - (a - 1) * cosine - beta);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record Coefficients(double b0, double b1, double b2, double a0, double a1, double a2) {
        double magnitudeDb(double omega) {
            double realNumerator = b0 + b1 * Math.cos(omega) + b2 * Math.cos(2 * omega);
            double imaginaryNumerator = -b1 * Math.sin(omega) - b2 * Math.sin(2 * omega);
            double realDenominator = a0 + a1 * Math.cos(omega) + a2 * Math.cos(2 * omega);
            double imaginaryDenominator = -a1 * Math.sin(omega) - a2 * Math.sin(2 * omega);
            double numerator = Math.hypot(realNumerator, imaginaryNumerator);
            double denominator = Math.hypot(realDenominator, imaginaryDenominator);
            return 20 * Math.log10(Math.max(1e-12, numerator / Math.max(1e-12, denominator)));
        }
    }
}
