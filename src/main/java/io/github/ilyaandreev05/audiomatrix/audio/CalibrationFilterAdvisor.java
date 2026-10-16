package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Conservative, offline-only PEQ suggestions from a relative calibration curve. */
public final class CalibrationFilterAdvisor {
    private static final double MIN_PROMINENCE_DB = 3.0;
    private static final double MAX_CUT_DB = 3.0;
    private static final double Q = 1.4;
    private static final int MAX_FILTERS = 3;

    private CalibrationFilterAdvisor() {}

    /** Returns up to three bounded cuts for interior response peaks; clipped or sparse curves are not used. */
    public static List<Suggestion> suggest(CalibrationMeasurementAnalyzer.Result result) {
        if (result == null || result.clipped()) return List.of();
        List<CalibrationMeasurementAnalyzer.Point> points = result.points();
        if (points == null || points.size() < 7) return List.of();
        for (int i = 0; i < points.size(); i++) {
            CalibrationMeasurementAnalyzer.Point point = points.get(i);
            if (point == null || !Double.isFinite(point.frequencyHz()) || point.frequencyHz() <= 0
                    || !Double.isFinite(point.relativeDb()) || (i > 0 && point.frequencyHz() <= points.get(i - 1).frequencyHz()))
                return List.of();
        }

        List<Suggestion> candidates = new ArrayList<>();
        for (int i = 2; i < points.size() - 2; i++) {
            double center = points.get(i).relativeDb();
            if (center < points.get(i - 1).relativeDb() || center < points.get(i + 1).relativeDb()) continue;
            double baseline = median(new double[]{points.get(i - 2).relativeDb(), points.get(i - 1).relativeDb(),
                    points.get(i + 1).relativeDb(), points.get(i + 2).relativeDb()});
            double prominence = center - baseline;
            if (prominence < MIN_PROMINENCE_DB) continue;
            double cut = Math.min(MAX_CUT_DB, Math.max(1.0, (prominence - 1.0) * 0.5));
            candidates.add(new Suggestion(points.get(i).frequencyHz(), -cut, Q, prominence));
        }

        List<Suggestion> strongest = new ArrayList<>();
        candidates.stream().sorted(Comparator.comparingDouble(Suggestion::prominenceDb).reversed())
                .forEach(candidate -> {
                    boolean overlaps = strongest.stream().anyMatch(existing ->
                            Math.abs(Math.log(candidate.frequencyHz() / existing.frequencyHz()) / Math.log(2.0)) < 0.25);
                    if (!overlaps && strongest.size() < MAX_FILTERS) strongest.add(candidate);
                });
        return strongest.stream().sorted(Comparator.comparingDouble(Suggestion::frequencyHz)).toList();
    }

    /** Predicts relative shape only; every predicted curve is independently median-normalized. */
    public static List<PreviewPoint> preview(CalibrationMeasurementAnalyzer.Result result, List<Suggestion> suggestions) {
        if (result == null || result.points() == null || result.points().isEmpty())
            throw new IllegalArgumentException("A calibration response curve is required");
        if (suggestions == null || suggestions.isEmpty())
            throw new IllegalArgumentException("At least one PEQ cut is required for preview");
        double[] predicted = new double[result.points().size()];
        for (int i = 0; i < result.points().size(); i++) {
            CalibrationMeasurementAnalyzer.Point point = result.points().get(i);
            if (!Double.isFinite(point.frequencyHz()) || point.frequencyHz() <= 0 || !Double.isFinite(point.relativeDb()))
                throw new IllegalArgumentException("The calibration curve contains invalid values");
            predicted[i] = point.relativeDb();
            for (Suggestion suggestion : suggestions) {
                validate(suggestion);
                predicted[i] += peakingGainDb(point.frequencyHz(), suggestion);
            }
        }
        double[] sorted = predicted.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        List<PreviewPoint> preview = new ArrayList<>(result.points().size());
        for (int i = 0; i < result.points().size(); i++) {
            CalibrationMeasurementAnalyzer.Point point = result.points().get(i);
            preview.add(new PreviewPoint(point.frequencyHz(), point.relativeDb(),
                    Math.max(-120.0, predicted[i] - median)));
        }
        return List.copyOf(preview);
    }

    private static double peakingGainDb(double frequencyHz, Suggestion suggestion) {
        double omega0 = 2.0 * Math.PI * suggestion.frequencyHz() / CalibrationSignal.SAMPLE_RATE;
        double omega = 2.0 * Math.PI * frequencyHz / CalibrationSignal.SAMPLE_RATE;
        double amplitude = Math.pow(10.0, suggestion.gainDb() / 40.0);
        double alpha = Math.sin(omega0) / (2.0 * suggestion.q());
        double cosine = Math.cos(omega0);
        double[] numerator = {1 + alpha * amplitude, -2 * cosine, 1 - alpha * amplitude};
        double[] denominator = {1 + alpha / amplitude, -2 * cosine, 1 - alpha / amplitude};
        double a0 = denominator[0];
        for (int i = 0; i < 3; i++) { numerator[i] /= a0; denominator[i] /= a0; }
        double numeratorMagnitude = magnitude(numerator, omega);
        double denominatorMagnitude = magnitude(denominator, omega);
        return 20.0 * Math.log10(Math.max(1e-12, numeratorMagnitude / denominatorMagnitude));
    }

    private static double magnitude(double[] coefficients, double omega) {
        double real = coefficients[0] + coefficients[1] * Math.cos(omega) + coefficients[2] * Math.cos(2 * omega);
        double imaginary = -coefficients[1] * Math.sin(omega) - coefficients[2] * Math.sin(2 * omega);
        return Math.hypot(real, imaginary);
    }

    private static double median(double[] values) {
        java.util.Arrays.sort(values);
        return (values[1] + values[2]) / 2.0;
    }

    private static void validate(Suggestion suggestion) {
        if (suggestion == null || !Double.isFinite(suggestion.frequencyHz()) || suggestion.frequencyHz() < 20
                || suggestion.frequencyHz() > 1000 || !Double.isFinite(suggestion.gainDb())
                || suggestion.gainDb() < -MAX_CUT_DB || suggestion.gainDb() >= 0
                || !Double.isFinite(suggestion.q()) || suggestion.q() < 0.5 || suggestion.q() > 4.0)
            throw new IllegalArgumentException("Only bounded, subtractive calibration PEQ cuts can be previewed");
    }

    public record Suggestion(double frequencyHz, double gainDb, double q, double prominenceDb) {}
    public record PreviewPoint(double frequencyHz, double beforeRelativeDb, double afterRelativeDb) {}
}
