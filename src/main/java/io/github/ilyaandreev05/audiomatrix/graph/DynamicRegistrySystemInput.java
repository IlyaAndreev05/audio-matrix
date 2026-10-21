package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session system input: the default sink whose monitor carries the sounds that no application
 * stream owns. It is served by one instance of the applications generator, so it reaches the same
 * Merge and MultiSwitch branch as the ordinary applications without becoming one of them.
 */
final class DynamicRegistrySystemInput {
    /** The stable application identity of the system input instance. */
    static final String APPLICATION_ID = "audiomatrix-system-input";
    /** The dedicated selector that keeps the system input out of the application rules. */
    static final String SELECTOR = "system-input";
    /** The bus role that tells the runtime to feed the system monitor into that bus. */
    static final String BUS_ROLE = "system_input";

    private DynamicRegistrySystemInput() {}

    static DynamicInstanceRegistry.InstanceSnapshot observation() {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("applicationId", APPLICATION_ID);
        binding.put("processName", "");
        binding.put("applicationName", "System input");
        binding.put("audioMatrixOwned", false);
        binding.put("hasPcmBinding", true);
        binding.put("systemInput", true);
        return new DynamicInstanceRegistry.InstanceSnapshot("applications", "app:" + APPLICATION_ID,
                "System input", SELECTOR, List.of("FL", "FR"), binding,
                DynamicInstanceRegistry.Confidence.HIGH);
    }
}
