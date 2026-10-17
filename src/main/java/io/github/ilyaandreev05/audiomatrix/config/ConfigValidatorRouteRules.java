package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidatorRouteRules {
    private ConfigValidatorRouteRules() {}
    static void validate(ConfigValidationContext ctx) {
        if (!ctx.profileIds.contains(String.valueOf(ctx.config.get("active_profile")))) ctx.errors.add("active_profile does not reference a profile id");
        for (int i = 0; i < ctx.routes.size(); i++) {
            Map<String, Object> route = ctx.routes.get(i);
            String from = String.valueOf(route.getOrDefault("from", ""));
            String to = String.valueOf(route.getOrDefault("to", ""));
            if (!ctx.busIds.contains(from)) ctx.errors.add("routes[" + i + "].from must reference a bus id");
            if (!ctx.busIds.contains(to) && !"system_default".equals(to) && !ctx.deviceIds.contains(to))
                ctx.errors.add("routes[" + i + "].to must reference a bus, device, or system_default");
            if (route.containsKey("name") && (!(route.get("name") instanceof String name) || name.isBlank()))
                ctx.errors.add("routes[" + i + "].name must be a non-empty string");
            ConfigValidationValues.checkVolume(route.getOrDefault("volume", 1.0), "routes[" + i + "].volume", ctx.errors);
            if (route.containsKey("gain_db")) {
                double gain = ConfigValidationValues.number(route.get("gain_db"), Double.NaN);
                if (!Double.isFinite(gain) || gain < -60 || gain > 24) ctx.errors.add("routes[" + i + "].gain_db must be between -60 and 24");
            }
            if (route.containsKey("delay_ms")) {
                double delay = ConfigValidationValues.number(route.get("delay_ms"), Double.NaN);
                if (!Double.isFinite(delay) || delay < 0 || delay > 2000) ctx.errors.add("routes[" + i + "].delay_ms must be between 0 and 2000");
            }
            if (route.containsKey("mute") && !(route.get("mute") instanceof Boolean)) ctx.errors.add("routes[" + i + "].mute must be boolean");
            if (route.containsKey("channel_map")) {
                Object channelMap = route.get("channel_map");
                if (!(channelMap instanceof String value) || !Set.of("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                        "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(value))
                    ctx.errors.add("routes[" + i + "].channel_map is unsupported");
                else if (!route.containsKey("channel_matrix")) {
                    String sourceBus = String.valueOf(route.getOrDefault("from", ""));
                    String layout = ctx.buses.stream().filter(bus -> sourceBus.equals(bus.get("id")))
                            .map(bus -> String.valueOf(bus.getOrDefault("channels", "stereo"))).findFirst().orElse("stereo");
                    if (!ConfigRouteValidation.supportsChannelMap(value, layout))
                        ctx.errors.add("routes[" + i + "].channel_map requires source bus channels unavailable in " + layout);
                }
            }
            if (route.containsKey("channel_matrix")) {
                try {
                    ChannelMatrix matrix = ChannelMatrix.parse(route.get("channel_matrix"));
                    String sourceBusId = String.valueOf(route.getOrDefault("from", ""));
                    String sourceLayout = ctx.buses.stream().filter(bus -> sourceBusId.equals(bus.get("id")))
                            .map(bus -> String.valueOf(bus.getOrDefault("channels", "stereo"))).findFirst().orElse("stereo");
                    if (!matrix.inputs().equals(ConfigRouteValidation.channelPositions(sourceLayout)))
                        ctx.errors.add("routes[" + i + "].channel_matrix.inputs must match the source bus channel layout " + sourceLayout);
                    Map<String, Object> targetBus = ctx.buses.stream().filter(bus -> to.equals(bus.get("id"))).findFirst().orElse(null);
                    if (targetBus != null) {
                        Set<String> targetPositions = Set.copyOf(ConfigRouteValidation.channelPositions(String.valueOf(targetBus.getOrDefault("channels", "stereo"))));
                        if (!targetPositions.containsAll(matrix.outputs()))
                            ctx.errors.add("routes[" + i + "].channel_matrix.outputs must exist on the target bus");
                    }
                } catch (IllegalArgumentException error) {
                    ctx.errors.add("routes[" + i + "].channel_matrix is invalid: " + error.getMessage());
                }
            }
            if (route.containsKey("polarity")) {
                Object polarityValue = route.get("polarity");
                String channelMap = String.valueOf(route.getOrDefault("channel_map", "stereo"));
                if (!(polarityValue instanceof String polarity)
                        || !Set.of("normal", "invert", "invert_left", "invert_right").contains(polarity))
                    ctx.errors.add("routes[" + i + "].polarity must be normal, invert, invert_left, or invert_right");
                else if (Set.of("invert_left", "invert_right").contains(polarity)) {
                    Set<String> outputPositions;
                    try {
                        outputPositions = route.containsKey("channel_matrix")
                                ? Set.copyOf(ChannelMatrix.parse(route.get("channel_matrix")).outputs())
                                : ConfigRouteValidation.monoChannelMap(channelMap) ? Set.of("MONO") : Set.of("FL", "FR");
                    } catch (IllegalArgumentException ignored) { outputPositions = Set.of(); }
                    String requested = "invert_left".equals(polarity) ? "FL" : "FR";
                    if (!outputPositions.contains(requested))
                        ctx.errors.add(route.containsKey("channel_matrix")
                                ? "routes[" + i + "].channel-specific polarity requires output channel " + requested
                                : "routes[" + i + "].channel-specific polarity requires a stereo channel map");
                }
            }
            if (route.containsKey("priority")) {
                double priority = ConfigValidationValues.number(route.get("priority"), Double.NaN);
                if (!Double.isFinite(priority) || priority != Math.rint(priority) || priority < 0 || priority > 1000)
                    ctx.errors.add("routes[" + i + "].priority must be an integer between 0 and 1000");
            }
            if (route.containsKey("fallback")) {
                Object fallback = route.get("fallback");
                if (!(fallback instanceof String fallbackId) || (!"system_default".equals(fallbackId) && !ctx.deviceIds.contains(fallbackId)))
                    ctx.errors.add("routes[" + i + "].fallback must reference an output device or system_default");
            }
            if (!(route.get("enabled") instanceof Boolean)) ctx.errors.add("routes[" + i + "].enabled must be boolean");
        }
        ConfigRouteValidation.checkRouteCycles(ctx.routes, ctx.busIds, ctx.errors);
    }
}
