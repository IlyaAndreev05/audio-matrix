package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidationContext {
    final Map<String, Object> config;
    final List<String> errors = new ArrayList<>();
    List<Map<String, Object>> devices = List.of();
    List<Map<String, Object>> applications = List.of();
    List<Map<String, Object>> buses = List.of();
    List<Map<String, Object>> routes = List.of();
    List<Map<String, Object>> profiles = List.of();
    List<Map<String, Object>> calibrations = List.of();
    Set<String> busIds = Set.of();
    Set<String> profileIds = Set.of();
    Set<String> deviceIds = Set.of();
    Set<String> routeIds = Set.of();
    Map<String, Object> routeChains = Map.of();

    ConfigValidationContext(Map<String, Object> config) { this.config = config; }
}
