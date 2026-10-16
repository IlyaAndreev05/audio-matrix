package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationFilterAdvisorTest {
    @Test
    void suggestsOnlyBoundedCutsForClearInteriorPeaks() {
        CalibrationMeasurementAnalyzer.Result curve = curve(new double[]{0, 0, 0, 1, 8, 1, 0, 0, 0}, false);

        List<CalibrationFilterAdvisor.Suggestion> suggestions = CalibrationFilterAdvisor.suggest(curve);

        assertEquals(1, suggestions.size());
        CalibrationFilterAdvisor.Suggestion cut = suggestions.getFirst();
        assertEquals(63, cut.frequencyHz(), 0.001);
        assertTrue(cut.gainDb() < 0 && cut.gainDb() >= -3);
        assertEquals(1.4, cut.q(), 0.001);
        assertTrue(cut.prominenceDb() >= 3);
    }

    @Test
    void omitsFlatClippedAndSparseCurves() {
        assertTrue(CalibrationFilterAdvisor.suggest(curve(new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, false)).isEmpty());
        assertTrue(CalibrationFilterAdvisor.suggest(curve(new double[]{0, 0, 0, 1, 8, 1, 0, 0, 0}, true)).isEmpty());
        assertTrue(CalibrationFilterAdvisor.suggest(curve(new double[]{0, 0, 0, 8, 0}, false)).isEmpty());
    }

    @Test
    void previewReducesOnlyAndRenormalizesThePredictedCurve() {
        CalibrationMeasurementAnalyzer.Result curve = curve(new double[]{0, 0, 0, 1, 8, 1, 0, 0, 0}, false);
        CalibrationFilterAdvisor.Suggestion cut = CalibrationFilterAdvisor.suggest(curve).getFirst();

        List<CalibrationFilterAdvisor.PreviewPoint> preview = CalibrationFilterAdvisor.preview(curve, List.of(cut));

        assertEquals(curve.points().size(), preview.size());
        assertTrue(preview.get(4).afterRelativeDb() < preview.get(4).beforeRelativeDb());
        assertTrue(preview.stream().allMatch(point -> Double.isFinite(point.afterRelativeDb())
                && point.afterRelativeDb() >= -120));
    }

    private static CalibrationMeasurementAnalyzer.Result curve(double[] levels, boolean clipped) {
        double[] frequencies = {25, 31.5, 40, 50, 63, 80, 100, 126, 160};
        List<CalibrationMeasurementAnalyzer.Point> points = new ArrayList<>();
        for (int i = 0; i < levels.length; i++)
            points.add(new CalibrationMeasurementAnalyzer.Point(frequencies[i], 0.1, levels[i], false));
        return new CalibrationMeasurementAnalyzer.Result(48_000, 0, 0.1, clipped, 0.1, points);
    }
}
