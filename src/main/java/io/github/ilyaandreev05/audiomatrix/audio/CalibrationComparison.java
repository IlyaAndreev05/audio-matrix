package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.TreeSet;

/** Exports and compares independently normalized calibration response curves. */
final class CalibrationComparison {
    private CalibrationComparison() {}

    static String toCsv(CalibrationMeasurementAnalyzer.Result result) {
        StringBuilder csv = new StringBuilder("frequency_hz,amplitude_pcm,relative_db,clipped\n");
        for (CalibrationMeasurementAnalyzer.Point point : result.points())
            csv.append(String.format(java.util.Locale.ROOT, "%.6f,%.9f,%.3f,%s%n", point.frequencyHz(),
                    point.amplitudePcm(), point.relativeDb(), point.clipped()));
        return csv.toString();
    }

    static CalibrationMeasurementAnalyzer.Comparison compare(CalibrationMeasurementAnalyzer.Result before,
                                                              CalibrationMeasurementAnalyzer.Result after) {
        validateCurve(before);
        validateCurve(after);
        double minimumHz = Math.max(before.points().getFirst().frequencyHz(), after.points().getFirst().frequencyHz());
        double maximumHz = Math.min(before.points().getLast().frequencyHz(), after.points().getLast().frequencyHz());
        if (!(maximumHz > minimumHz))
            throw new IllegalArgumentException("The response curves have no common frequency range");
        TreeSet<Double> frequencies = new TreeSet<>();
        addFrequencies(frequencies, before.points(), minimumHz, maximumHz);
        addFrequencies(frequencies, after.points(), minimumHz, maximumHz);
        if (frequencies.size() < 2)
            throw new IllegalArgumentException("The response curves need at least two shared frequency points");
        List<CalibrationMeasurementAnalyzer.ComparisonPoint> points = frequencies.stream().map(frequency -> {
            double beforeDb = interpolate(before.points(), frequency);
            double afterDb = interpolate(after.points(), frequency);
            return new CalibrationMeasurementAnalyzer.ComparisonPoint(frequency, beforeDb, afterDb, afterDb - beforeDb);
        }).toList();
        return new CalibrationMeasurementAnalyzer.Comparison(points);
    }

    private static void addFrequencies(TreeSet<Double> destination,
                                       List<CalibrationMeasurementAnalyzer.Point> points,
                                       double minimumHz, double maximumHz) {
        for (CalibrationMeasurementAnalyzer.Point point : points)
            if (point.frequencyHz() >= minimumHz && point.frequencyHz() <= maximumHz)
                destination.add(point.frequencyHz());
    }

    private static void validateCurve(CalibrationMeasurementAnalyzer.Result result) {
        if (result == null || result.points().size() < 2)
            throw new IllegalArgumentException("Each response curve needs at least two points");
        double previous = 0;
        for (CalibrationMeasurementAnalyzer.Point point : result.points()) {
            if (point == null || !Double.isFinite(point.frequencyHz()) || point.frequencyHz() <= previous
                    || !Double.isFinite(point.relativeDb()))
                throw new IllegalArgumentException("Response curve points must have increasing positive frequencies and finite levels");
            previous = point.frequencyHz();
        }
    }

    private static double interpolate(List<CalibrationMeasurementAnalyzer.Point> points, double frequencyHz) {
        int low = 0, high = points.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            double current = points.get(middle).frequencyHz();
            if (current == frequencyHz) return points.get(middle).relativeDb();
            if (current < frequencyHz) low = middle + 1;
            else high = middle - 1;
        }
        CalibrationMeasurementAnalyzer.Point left = points.get(high), right = points.get(low);
        double fraction = (Math.log(frequencyHz) - Math.log(left.frequencyHz()))
                / (Math.log(right.frequencyHz()) - Math.log(left.frequencyHz()));
        return left.relativeDb() + fraction * (right.relativeDb() - left.relativeDb());
    }
}
