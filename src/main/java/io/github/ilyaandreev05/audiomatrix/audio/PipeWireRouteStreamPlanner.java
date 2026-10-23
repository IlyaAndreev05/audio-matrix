package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

/** Resolves which live playback streams join the route graph and how their levels are controlled. */
final class PipeWireRouteStreamPlanner {
    @FunctionalInterface
    interface ApplicationFilterReader {
        List<Map<String, Object>> read(Map<String, Object> rule, Map<String, Object> config) throws IOException;
    }

    private PipeWireRouteStreamPlanner() {}

    static List<StreamPlan> plan(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                  Map<String, BusPlan> buses, Map<String, CapturedStreamBinding> capturedBindings,
                                  ApplicationFilterReader filters) throws IOException {
        List<StreamPlan> streams = new ArrayList<>();
        List<Map<String, Object>> applicationRules = PipeWireRouteStaticSupport01.maps(config.get("applications"));
        Map<String, Map<String, Object>> rulesById = CapturedStreamRuleResolver.indexRules(applicationRules);
        List<String> eventInputBuses = EventInputStreamPlanner.append(config, buses, streams, filters::read);
        for (AudioNode stream : snapshot.streams()) {
            StreamPlan planned = planStream(config, snapshot, buses, capturedBindings, applicationRules,
                    rulesById, eventInputBuses, stream, filters);
            if (planned != null) streams.add(planned);
        }
        return streams;
    }

    private static StreamPlan planStream(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                         Map<String, BusPlan> buses, Map<String, CapturedStreamBinding> capturedBindings,
                                         List<Map<String, Object>> applicationRules,
                                         Map<String, Map<String, Object>> rulesById, List<String> eventInputBuses,
                                         AudioNode stream, ApplicationFilterReader filters) throws IOException {
        if (ApplicationStreamEligibility.isTransientShellSound(stream) || AudioMatrixOwnedStream.matches(stream)) return null;
        boolean captureName = stream.name().startsWith(CapturedStreamBinding.NODE_PREFIX);
        CapturedStreamBinding binding = capturedBindings == null ? null : capturedBindings.get(stream.name());
        if (captureName && (binding == null || !stream.name().equals(binding.nodeName()))) return null;
        boolean eventStream = PipeWireRouteStaticSupport03.isEventSoundStudioStream(stream);
        if (eventStream && !eventInputBuses.isEmpty()) return null;
        Map<String, Object> rule;
        String sourceNodeId = "", instanceKey = "";
        if (binding != null) {
            rule = CapturedStreamRuleResolver.findRule(binding, stream, snapshot.streams(), rulesById);
            if (rule == null) return null;
            sourceNodeId = binding.sourceNodeId();
            instanceKey = binding.instanceKey();
        } else {
            rule = eventStream ? PipeWireRouteStaticSupport03.explicitEventApplicationRule(applicationRules)
                    : ApplicationRuleMatcher.find(applicationRules, stream);
        }
        if (rule == null && !eventStream) {
            String defaultBus = PipeWireRouteStaticSupport01.text(config.get("graph_default_bus"));
            if (!captureName && !defaultBus.isBlank() && buses.containsKey(defaultBus))
                rule = Map.of("bus", defaultBus);
        }
        if (rule == null) return null;
        String busId = PipeWireRouteStaticSupport01.text(rule.get("bus"));
        if (!buses.containsKey(busId)) return null;
        List<Map<String, Object>> applicationFilters = filters.read(rule, config);
        List<String> positions = PipeWireRouteStaticSupport04.streamPositions(stream.channels());
        String key = "application:" + stream.name();
        String input = PipeWireRouteStaticSupport01.applicationNodeName("dsp_input", stream.name());
        String output = PipeWireRouteStaticSupport01.applicationNodeName("dsp_output", stream.name());
        if (!applicationFilters.isEmpty())
            PipeWireFilterChainConfig.render("Audio Matrix application EQ " + stream.applicationName(),
                    input, output, positions, applicationFilters, PipeWireRouteStaticSupport03.limiterEnabled(config));
        return new StreamPlan(stream, sourceNodeId, instanceKey, busId, applicationFilters, positions, key, input, output);
    }

}
