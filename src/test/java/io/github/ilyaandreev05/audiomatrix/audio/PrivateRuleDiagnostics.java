package io.github.ilyaandreev05.audiomatrix.audio;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostics for streams created by the private PipeWire live-rule tests. */
final class PrivateRuleDiagnostics {
    private PrivateRuleDiagnostics() { }

    static void diagnostic(String phase, List<AudioNode> nodes, AtomicReference<Throwable> failure) {
        for (AudioNode node : nodes) {
            try {
                String volume = PipeWireBackend.run(List.of("wpctl", "get-volume", Integer.toString(node.id())), Duration.ofSeconds(5));
                System.out.println("RULE-DIAG " + phase + " id=" + node.id() + " name=" + node.name()
                        + " appId=" + node.applicationId() + " process=" + node.processName()
                        + " appName=" + node.applicationName() + " volume=" + volume.trim().replace('\n', ' ')
                        + " callback=" + failure.get());
            } catch (Exception error) {
                System.out.println("RULE-DIAG " + phase + " id=" + node.id() + " read-error=" + error);
            }
        }
    }
}
