package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimiterNativeTest {
    @Test
    void linkedLookaheadLimiterKeepsDigitalPeakBelowConfiguredCeiling() throws Exception {
        String executable = System.getProperty("audioMatrix.limiterHarness");
        assertNotNull(executable, "Gradle must compile the native limiter harness before running tests");
        Process process = new ProcessBuilder(Path.of(executable).toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "native limiter harness timed out");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("linked=ok"), output);
    }
}
