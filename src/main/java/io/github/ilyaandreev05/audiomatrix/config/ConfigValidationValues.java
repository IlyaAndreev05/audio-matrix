package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidationValues {
    private ConfigValidationValues() {}
    static List<Map<String,Object>> optionalList(Object value,String path,List<String> errors){
        if(value==null)return new ArrayList<>();if(!(value instanceof List<?> list)){errors.add(path+" must be an array");return new ArrayList<>();}
        List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<list.size();i++)if(list.get(i) instanceof Map<?,?> map){Map<String,Object>item=new java.util.LinkedHashMap<>();map.forEach((key,entry)->item.put(String.valueOf(key),entry));result.add(item);}else errors.add(path+"["+i+"] must be an object");return result;
    }
    static void validateVolumes(List<Map<String, Object>> items, String path, List<String> errors) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).containsKey("volume"))
            checkVolume(items.get(i).get("volume"), path + "[" + i + "].volume", errors);
    }
    static void bounded(Object value, double minimum, double maximum, String path, List<String> errors) {
        double number = number(value, Double.NaN);
        if (!Double.isFinite(number) || number < minimum || number > maximum)
            errors.add(path + " must be between " + minimum + " and " + maximum);
    }
    static void checkVolume(Object value, String path, List<String> errors) {
        double volume = number(value, Double.NaN);
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) errors.add(path + " must be a number in the range 0.0–1.0");
    }
    static void checkUniqueIds(List<Map<String, Object>> items, String path, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Object id = items.get(i).get("id");
            if (!(id instanceof String text) || text.isBlank()) errors.add(path + "[" + i + "].id must be a non-empty string");
            else if (!seen.add(text)) errors.add(path + " contains duplicate id " + text);
        }
    }
    static Set<String> ids(List<Map<String, Object>> items) {
        Set<String> result = new HashSet<>(); for (Map<String, Object> item : items) if (item.get("id") instanceof String id) result.add(id); return result;
    }
    static Set<String> deviceIds(List<Map<String, Object>> items) { return ids(items); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> list(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> source)) { errors.add(path + " must be an array"); return List.of(); }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
            else errors.add(path + "[" + i + "] must be an object");
        }
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value, String path, List<String> errors) {
        if (value == null) { errors.add(path + " is required"); return null; }
        if (!(value instanceof Map<?, ?>)) { errors.add(path + " must be an object"); return null; }
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> optionalObject(Object value, String path, List<String> errors) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) { errors.add(path + " must be an object"); return Map.of(); }
        return (Map<String, Object>) map;
    }
    static void required(Map<String, Object> map, String key, Class<?> type, List<String> errors) {
        if (!type.isInstance(map.get(key))) errors.add(key + " must be " + type.getSimpleName());
    }
    static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }}
