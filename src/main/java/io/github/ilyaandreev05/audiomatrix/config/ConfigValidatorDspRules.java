package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidatorDspRules {
    private ConfigValidatorDspRules() {}
    static void validate(ConfigValidationContext ctx) {
        Map<String, Object> dsp = ConfigValidationValues.object(ctx.config.get("dsp"), "dsp", ctx.errors);
        ctx.routeChains = Map.of();
        if (dsp != null) {
            ConfigEqRules.validateEq(dsp.get("global_eq"), "dsp.global_eq", ctx.errors);
            ctx.routeChains = ConfigValidationValues.optionalObject(dsp.get("route_chains"), "dsp.route_chains", ctx.errors);
            Map<String, Object> deviceEq = ConfigValidationValues.optionalObject(dsp.get("devices"), "dsp.devices", ctx.errors);
            deviceEq.forEach((deviceId, eq) -> ConfigEqRules.validateEq(eq, "dsp.devices." + deviceId, ctx.errors));
            ctx.routeChains.forEach((chainId, eq) -> ConfigEqRules.validateEq(eq, "dsp.route_chains." + chainId, ctx.errors));
            Map<String, Object> limiter = ConfigValidationValues.optionalObject(dsp.get("limiter"), "dsp.limiter", ctx.errors);
            if (!limiter.isEmpty()) {
                if (!(limiter.get("enabled") instanceof Boolean)) ctx.errors.add("dsp.limiter.enabled must be boolean");
                ConfigValidationValues.bounded(limiter.get("ceiling_db"), -12, 0, "dsp.limiter.ceiling_db", ctx.errors);
                ConfigValidationValues.bounded(limiter.get("lookahead_ms"), 1, 10, "dsp.limiter.lookahead_ms", ctx.errors);
                ConfigValidationValues.bounded(limiter.get("release_ms"), 5, 500, "dsp.limiter.release_ms", ctx.errors);
            }
            if (!Boolean.TRUE.equals(limiter.get("enabled"))) {
                for (int i = 0; i < ctx.routes.size(); i++) {
                    String channelMap = String.valueOf(ctx.routes.get(i).getOrDefault("channel_map", "stereo"));
                    if (!Boolean.FALSE.equals(ctx.routes.get(i).get("enabled")) && !ctx.routes.get(i).containsKey("channel_matrix")
                            && ConfigRouteValidation.channelMapMaySum(channelMap))
                        ctx.errors.add("routes[" + i + "].channel_map requires the output limiter because it sums channels");
                    if (!Boolean.FALSE.equals(ctx.routes.get(i).get("enabled")) && ctx.routes.get(i).containsKey("channel_matrix")) {
                        try {
                            if (ChannelMatrix.parse(ctx.routes.get(i).get("channel_matrix")).maximumRowGain() > 1.0 + 1e-9)
                                ctx.errors.add("routes[" + i + "].channel_matrix requires the output limiter because it can increase signal level");
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        }
        for (int i = 0; i < ctx.buses.size(); i++) {
            Object ref = ctx.buses.get(i).get("filters_ref");
            ConfigRouteValidation.validateRouteChainReference(ref, ctx.routeChains, "buses[" + i + "].filters_ref", ctx.errors);
        }
        for (int i = 0; i < ctx.applications.size(); i++) {
            Object ref = ctx.applications.get(i).get("filters_ref");
            if (ref != null) ConfigRouteValidation.validateRouteChainReference(ref, ctx.routeChains, "applications[" + i + "].filters_ref", ctx.errors);
        }
        for (int i = 0; i < ctx.devices.size(); i++) {
            Object ref = ctx.devices.get(i).get("filters_ref");
            if (ref != null) ConfigRouteValidation.validateRouteChainReference(ref, ctx.routeChains, "devices[" + i + "].filters_ref", ctx.errors);
        }
        for (int i = 0; i < ctx.routes.size(); i++) {
            Object ref = ctx.routes.get(i).get("filters_ref");
            if (ref != null) ConfigRouteValidation.validateRouteChainReference(ref, ctx.routeChains, "routes[" + i + "].filters_ref", ctx.errors);
            String channelMap = String.valueOf(ctx.routes.get(i).getOrDefault("channel_map", "stereo"));
            Set<String> positions;
            try {
                positions = ctx.routes.get(i).containsKey("channel_matrix")
                        ? Set.copyOf(ChannelMatrix.parse(ctx.routes.get(i).get("channel_matrix")).outputs())
                        : ConfigRouteValidation.monoChannelMap(channelMap) ? Set.of("MONO") : Set.of("FL", "FR");
            } catch (IllegalArgumentException ignored) {
                positions = Set.of();
            }
            ConfigRouteValidation.validateRouteChainChannels(ref, ctx.routeChains, positions, "routes[" + i + "].filters_ref", ctx.errors);
            String fromBusId = String.valueOf(ctx.routes.get(i).getOrDefault("from", ""));
            for (int busIndex = 0; busIndex < ctx.buses.size(); busIndex++) {
                Map<String, Object> bus = ctx.buses.get(busIndex);
                if (fromBusId.equals(bus.get("id"))) {
                    Object busRef = bus.get("filters_ref");
                    ConfigRouteValidation.validateRouteChainChannels(busRef, ctx.routeChains, positions,
                            "buses[" + busIndex + "].filters_ref for route " + i, ctx.errors);
                    break;
                }
            }
        }
    }
}
