package io.github.ilyaandreev05.audiomatrix.audio;

/** Fits a DC offset plus one sinusoid to a sampled calibration window. */
final class CalibrationSignalFit {
    private CalibrationSignalFit() {}

    static Fit fit(double[] samples, int first, int count, int sampleRate, double frequency) {
        return fit(samples, first, count, sampleRate, frequency, 1);
    }

    static Fit fit(double[] samples, int first, int count, int sampleRate,
                   double frequency, int sampleStride) {
        double[][] normal = new double[3][4];
        double omega = 2.0 * Math.PI * frequency * sampleStride / sampleRate;
        double sine = 0, cosine = 1;
        double sineStep = Math.sin(omega), cosineStep = Math.cos(omega), sumSquares = 0;
        for (int i = 0; i < count; i++) {
            double sample = samples[first + i * sampleStride];
            normal[0][3] += sample;
            normal[1][3] += sine * sample;
            normal[2][3] += cosine * sample;
            normal[0][0] += 1;
            normal[0][1] += sine;
            normal[0][2] += cosine;
            normal[1][1] += sine * sine;
            normal[1][2] += sine * cosine;
            normal[2][2] += cosine * cosine;
            sumSquares += sample * sample;
            double nextSine = sine * cosineStep + cosine * sineStep;
            cosine = cosine * cosineStep - sine * sineStep;
            sine = nextSine;
        }
        mirrorSymmetricTerms(normal);
        double[] rhs = {normal[0][3], normal[1][3], normal[2][3]};
        solve(normal);
        double mean = rhs[0] / count;
        double total = Math.max(0, sumSquares - rhs[0] * mean);
        double explained = normal[0][3] * rhs[0] + normal[1][3] * rhs[1]
                + normal[2][3] * rhs[2] - rhs[0] * mean;
        double score = total <= 1e-15 ? 0 : Math.max(0, Math.min(1, explained / total));
        return new Fit(Math.hypot(normal[1][3], normal[2][3]), score);
    }

    private static void mirrorSymmetricTerms(double[][] normal) {
        normal[1][0] = normal[0][1];
        normal[2][0] = normal[0][2];
        normal[2][1] = normal[1][2];
    }

    private static void solve(double[][] normal) {
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 3; row++)
                if (Math.abs(normal[row][pivot]) > Math.abs(normal[best][pivot])) best = row;
            if (Math.abs(normal[best][pivot]) < 1e-10)
                throw new IllegalArgumentException("Sweep window is too short for the selected frequency");
            swapRows(normal, pivot, best);
            normalizePivot(normal, pivot);
            eliminateColumn(normal, pivot);
        }
    }

    private static void swapRows(double[][] matrix, int first, int second) {
        double[] swap = matrix[first];
        matrix[first] = matrix[second];
        matrix[second] = swap;
    }

    private static void normalizePivot(double[][] matrix, int pivot) {
        double scale = matrix[pivot][pivot];
        for (int column = pivot; column < 4; column++) matrix[pivot][column] /= scale;
    }

    private static void eliminateColumn(double[][] matrix, int pivot) {
        for (int row = 0; row < 3; row++) if (row != pivot) {
            double factor = matrix[row][pivot];
            for (int column = pivot; column < 4; column++) matrix[row][column] -= factor * matrix[pivot][column];
        }
    }

    record Fit(double amplitude, double toneFitScore) {}
}
