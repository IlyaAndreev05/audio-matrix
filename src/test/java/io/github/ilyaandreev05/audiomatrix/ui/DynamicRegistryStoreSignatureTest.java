package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.RouteInputSignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The route input signature decides whether a saved graph change is applied to
 * the running graph. The generated port list is registry churn and must stay
 * out of the signature, but the user's choice of a generated branch is a
 * routing decision: toggling it has to reach the signature, otherwise the
 * choice is saved but the live route never changes.
 */
class DynamicRegistryStoreSignatureTest {

    @Test void togglingAGeneratedBranchChangesTheRouteSignature() {
        Map<String, Object> enabled = configWithSwitch(
                List.of("monitor", "generated-output-2f1c"), List.of("in->generated-output-2f1c"));
        Map<String, Object> disabled = configWithSwitch(
                List.of("monitor", "generated-output-2f1c"), List.of());
        assertNotEquals(signature(enabled), signature(disabled),
                "A generated branch that the user switches off must change the route signature");
    }

    @Test void generatedPortDiscoveryAloneKeepsTheRouteSignature() {
        Map<String, Object> before = configWithSwitch(List.of("monitor"), List.of("in->monitor"));
        Map<String, Object> discovered = configWithSwitch(
                List.of("monitor", "generated-output-9a7b"), List.of("in->monitor"));
        assertEquals(signature(before), signature(discovered),
                "A newly discovered generated port must not change the route signature on its own");
    }

    @Test void staticBranchChoiceStillChangesTheRouteSignature() {
        Map<String, Object> monitor = configWithSwitch(List.of("monitor", "vibro"), List.of("in->monitor"));
        Map<String, Object> vibro = configWithSwitch(List.of("monitor", "vibro"), List.of("in->vibro"));
        assertNotEquals(signature(monitor), signature(vibro));
    }

    private static String signature(Map<String, Object> config) {
        return RouteInputSignature.create(DynamicRegistryStore.stableConfig(config), null, 1L);
    }

    private static Map<String, Object> configWithSwitch(List<String> outputPorts, List<String> activeLinks) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("multiple", true);
        parameters.put("input_ports", List.of("in"));
        parameters.put("output_ports", new ArrayList<>(outputPorts));
        parameters.put("active_links", new ArrayList<>(activeLinks));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "octine-main-switch");
        node.put("name", "Main");
        node.put("type", "switch");
        node.put("parameters", parameters);
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("id", "main");
        route.put("nodes", new ArrayList<>(List.of(node)));
        route.put("connections", new ArrayList<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("route_library", new ArrayList<>(List.of(route)));
        return config;
    }
}
