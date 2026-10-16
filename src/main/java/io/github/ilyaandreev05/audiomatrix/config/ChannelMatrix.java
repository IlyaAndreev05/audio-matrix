package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User-authored linear channel transform. Each coefficient row belongs to one output. */
public record ChannelMatrix(List<String> inputs, List<String> outputs, List<List<Double>> coefficients) {
    public static final Set<String> CHANNELS = Set.of("MONO", "FL", "FR", "FC", "LFE", "RL", "RR", "RC", "SL", "SR");

    public ChannelMatrix {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        coefficients = coefficients.stream().map(List::copyOf).toList();
        validatePositions(inputs, "inputs");
        validatePositions(outputs, "outputs");
        if (coefficients.size() != outputs.size())
            throw new IllegalArgumentException("coefficients must contain one row per output channel");
        boolean anyNonZero = false;
        for (int rowIndex = 0; rowIndex < coefficients.size(); rowIndex++) {
            List<Double> row = coefficients.get(rowIndex);
            if (row.size() != inputs.size())
                throw new IllegalArgumentException("coefficient row " + rowIndex + " must contain one value per input channel");
            for (Double coefficient : row) {
                if (coefficient == null || !Double.isFinite(coefficient) || coefficient < -1.0 || coefficient > 1.0)
                    throw new IllegalArgumentException("matrix coefficients must be finite and between -1 and 1");
                anyNonZero |= coefficient != 0.0;
            }
        }
        if (!anyNonZero) throw new IllegalArgumentException("at least one matrix coefficient must be non-zero");
    }

    public static ChannelMatrix parse(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("channel_matrix must be an object");
        List<String> inputs = positions(map.get("inputs"), "inputs");
        List<String> outputs = positions(map.get("outputs"), "outputs");
        Object rawRows = map.get("coefficients");
        if (!(rawRows instanceof List<?> rows) || rows.size() != outputs.size())
            throw new IllegalArgumentException("coefficients must contain one row per output channel");
        List<List<Double>> coefficients = new ArrayList<>();
        boolean anyNonZero = false;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (!(rows.get(rowIndex) instanceof List<?> row) || row.size() != inputs.size())
                throw new IllegalArgumentException("coefficient row " + rowIndex + " must contain one value per input channel");
            List<Double> values = new ArrayList<>();
            for (Object raw : row) {
                if (!(raw instanceof Number number)) throw new IllegalArgumentException("matrix coefficients must be numbers");
                double coefficient = number.doubleValue();
                if (!Double.isFinite(coefficient) || coefficient < -1.0 || coefficient > 1.0)
                    throw new IllegalArgumentException("matrix coefficients must be finite and between -1 and 1");
                anyNonZero |= coefficient != 0.0;
                values.add(coefficient);
            }
            coefficients.add(List.copyOf(values));
        }
        if (!anyNonZero) throw new IllegalArgumentException("at least one matrix coefficient must be non-zero");
        return new ChannelMatrix(inputs, outputs, coefficients);
    }

    public double maximumRowGain() {
        return coefficients.stream().mapToDouble(row -> row.stream().mapToDouble(Math::abs).sum()).max().orElse(0.0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputs", inputs);
        result.put("outputs", outputs);
        result.put("coefficients", coefficients);
        return result;
    }

    private static List<String> positions(Object value, String field) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 8)
            throw new IllegalArgumentException(field + " must contain between 1 and 8 channel names");
        List<String> positions = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String channel) || !CHANNELS.contains(channel))
                throw new IllegalArgumentException(field + " contains an unsupported channel: " + item);
            if (positions.contains(channel)) throw new IllegalArgumentException(field + " contains duplicate channel " + channel);
            positions.add(channel);
        }
        return List.copyOf(positions);
    }

    private static void validatePositions(List<String> positions, String field) {
        if (positions.isEmpty() || positions.size() > 8)
            throw new IllegalArgumentException(field + " must contain between 1 and 8 channel names");
        Set<String> seen = new java.util.HashSet<>();
        for (String position : positions) {
            if (!CHANNELS.contains(position)) throw new IllegalArgumentException(field + " contains an unsupported channel: " + position);
            if (!seen.add(position)) throw new IllegalArgumentException(field + " contains duplicate channel " + position);
        }
    }
}
