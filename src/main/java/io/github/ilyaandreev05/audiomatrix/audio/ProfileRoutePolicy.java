package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.Set;

/** Pure mode-to-route policy. Specialized modes require explicit source-bus and output roles. */
public final class ProfileRoutePolicy {
    private ProfileRoutePolicy() {}

    public static Decision evaluate(String mode, String sourceBusRole, String outputRole, String routeId) {
        return evaluate(mode, sourceBusRole, Set.of(sourceBusRole == null ? "general" : sourceBusRole), outputRole, routeId);
    }

    public static Decision evaluate(String mode, String sourceBusRole, Set<String> sourceRootRoles,
                                    String outputRole, String routeId) {
        String selectedMode = mode == null ? "hybrid" : mode;
        if ("ambiguous".equals(outputRole) && !"hybrid".equals(selectedMode) && !"multi-output".equals(selectedMode)) {
            return new Decision(false, "route " + routeId + " skipped: multiple device selectors match the output; make the output selector unique");
        }
        return switch (selectedMode) {
            case "haptic" -> {
                if (!Set.of("effects").equals(sourceRootRoles))
                    yield skip(routeId, selectedMode, "the source path must originate only from an effects bus");
                if (!"haptic_output".equals(outputRole))
                    yield skip(routeId, selectedMode, "output role haptic_output is required");
                yield Decision.allow();
            }
            case "subwoofer" -> {
                if (!"subwoofer".equals(sourceBusRole))
                    yield skip(routeId, selectedMode, "the final branch must come from a subwoofer bus");
                if (!(Set.of("music").equals(sourceRootRoles) || Set.of("subwoofer").equals(sourceRootRoles)))
                    yield skip(routeId, selectedMode, "the subwoofer branch must receive only music or subwoofer sources");
                if (!"subwoofer".equals(outputRole))
                    yield skip(routeId, selectedMode, "output role subwoofer is required");
                yield Decision.allow();
            }
            case "speaker" -> {
                boolean speaker = "speaker".equals(outputRole) || "speakers".equals(outputRole);
                yield speaker ? Decision.allow() : skip(routeId, selectedMode, "output role speaker is required");
            }
            case "hybrid", "multi-output" -> Decision.allow();
            case "off" -> new Decision(false, "route " + routeId + " skipped: profile mode off disables routes");
            default -> Decision.allow();
        };
    }

    private static Decision skip(String routeId, String mode, String reason) {
        return new Decision(false, "route " + routeId + " skipped: profile mode " + mode + " requires " + reason);
    }

    public static String builtInBusRole(String busId) {
        return switch (busId == null ? "" : busId) {
            case "effects" -> "effects";
            case "music" -> "music";
            case "subwoofer" -> "subwoofer";
            default -> "general";
        };
    }

    public record Decision(boolean allowed, String warning) {
        static Decision allow() { return new Decision(true, ""); }
    }
}
