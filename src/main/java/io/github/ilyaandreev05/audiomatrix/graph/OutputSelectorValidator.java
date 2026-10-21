package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

final class OutputSelectorValidator {
    private OutputSelectorValidator() {}

    static boolean supports(String selector) {
        return parse(selector) != null;
    }
    static boolean supports(String registry, String selector) {
        return switch (registry) {
            case "applications" -> "application:unassigned".equals(selector) || "application:*".equals(selector)
                    || (selector.startsWith("application:") && !selector.substring("application:".length()).isBlank())
                    || (selector.startsWith("process:") && !selector.substring("process:".length()).isBlank());
            case "outputs" -> supports(selector);
            case "browser_tabs" -> "browser:*".equals(selector) || "browser_tabs:*".equals(selector)
                    || (selector.startsWith("browser:") && !selector.substring("browser:".length()).isBlank());
            default -> false;
        };
    }


    static void validatePartitions(List<GraphNode> nodes, List<String> errors) {
        List<DynamicGeneratorNode> outputs = nodes.stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> "outputs".equals(node.registry())).toList();
        for (DynamicGeneratorNode generator : outputs) {
            if (!supports(generator.selector()))
                errors.add("Dynamic output generator " + generator.id()
                        + " selector must be device:*, device:all, device:role:<nonblank>, or a nonblank device identity");
        }
        for (int first = 0; first < outputs.size(); first++) {
            for (int second = first + 1; second < outputs.size(); second++) {
                DynamicGeneratorNode left = outputs.get(first), right = outputs.get(second);
                SelectorIntersection overlap = intersection(parse(left.selector()), parse(right.selector()));
                if (overlap == null || excludes(left, overlap) || excludes(right, overlap)) continue;
                errors.add("Dynamic output generators " + left.id() + " and " + right.id()
                        + " overlap on " + overlap.description()
                        + "; exclude that selector in one generator's excluded_selectors");
            }
        }
    }

    private static boolean excludes(DynamicGeneratorNode generator, SelectorIntersection overlap) {
        for (String value : generator.excludedSelectors()) {
            OutputSelector excluded = parse(value);
            if (excluded == null) continue;
            if (excluded.kind() == Kind.WILDCARD) return true;
            if (excluded.kind() == Kind.IDENTITY && overlap.identity() != null
                    && overlap.identity().equals(excluded.value())) return true;
            if (excluded.kind() == Kind.ROLE && overlap.role() != null
                    && overlap.role().equals(excluded.value())) return true;
        }
        return false;
    }

    private static SelectorIntersection intersection(OutputSelector left, OutputSelector right) {
        if (left == null || right == null) return null;
        Constraint identity = mergeConstraint(left, right, Kind.IDENTITY);
        Constraint role = mergeConstraint(left, right, Kind.ROLE);
        if (identity.conflict() || role.conflict()) return null;
        return new SelectorIntersection(identity.value(), role.value());
    }

    private static Constraint mergeConstraint(OutputSelector left, OutputSelector right, Kind kind) {
        String first = left.kind() == kind ? left.value() : null;
        String second = right.kind() == kind ? right.value() : null;
        if (first != null && second != null && !first.equals(second)) return new Constraint(true, null);
        return new Constraint(false, first == null ? second : first);
    }

    private record Constraint(boolean conflict, String value) {}

    private static OutputSelector parse(String selector) {
        if ("device:*".equals(selector) || "device:all".equals(selector))
            return new OutputSelector(Kind.WILDCARD, "");
        if (selector == null || !selector.startsWith("device:")) return null;
        String value = selector.substring("device:".length());
        if (value.isBlank()) return null;
        if (value.startsWith("role:")) {
            String role = value.substring("role:".length());
            return role.isBlank() ? null : new OutputSelector(Kind.ROLE, role);
        }
        return new OutputSelector(Kind.IDENTITY, value);
    }

    private enum Kind { WILDCARD, ROLE, IDENTITY }
    private record OutputSelector(Kind kind, String value) {}
    private record SelectorIntersection(String identity, String role) {
        String description() {
            if (identity != null) return "device:" + identity;
            if (role != null) return "device:role:" + role;
            return "all output devices";
        }
    }
}
