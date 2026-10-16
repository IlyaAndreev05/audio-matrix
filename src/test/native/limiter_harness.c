#include "../../main/native/audiomatrix_limiter.c"

#include <stdio.h>

#define SAMPLE_RATE 48000UL
#define SAMPLE_COUNT 8192UL
#define BLOCK_SIZE 256UL

int main(void) {
    const LADSPA_Descriptor *plugin = ladspa_descriptor(0);
    if (plugin == NULL || plugin->PortCount != PORTS || plugin->instantiate == NULL || plugin->run == NULL) {
        fputs("invalid LADSPA descriptor\n", stderr);
        return 1;
    }

    LADSPA_Handle limiter = plugin->instantiate(plugin, SAMPLE_RATE);
    if (limiter == NULL) {
        fputs("limiter allocation failed\n", stderr);
        return 1;
    }

    LADSPA_Data inputs[CHANNELS][SAMPLE_COUNT] = {{0}};
    LADSPA_Data outputs[CHANNELS][SAMPLE_COUNT] = {{0}};
    LADSPA_Data ceiling_db = -1.0f;
    LADSPA_Data lookahead_ms = 5.0f;
    LADSPA_Data release_ms = 50.0f;
    for (unsigned long channel = 0; channel < CHANNELS; channel++) {
        plugin->connect_port(limiter, channel, inputs[channel]);
        plugin->connect_port(limiter, CHANNELS + channel, outputs[channel]);
    }
    plugin->connect_port(limiter, CHANNELS * 2, &ceiling_db);
    plugin->connect_port(limiter, CHANNELS * 2 + 1, &lookahead_ms);
    plugin->connect_port(limiter, CHANNELS * 2 + 2, &release_ms);
    if (plugin->activate != NULL) plugin->activate(limiter);

    for (unsigned long frame = 2000; frame < 5200; frame++) {
        inputs[0][frame] = 2.0f;
        inputs[1][frame] = 0.5f;
    }

    for (unsigned long offset = 0; offset < SAMPLE_COUNT; offset += BLOCK_SIZE) {
        unsigned long count = SAMPLE_COUNT - offset < BLOCK_SIZE ? SAMPLE_COUNT - offset : BLOCK_SIZE;
        for (unsigned long channel = 0; channel < CHANNELS; channel++) {
            plugin->connect_port(limiter, channel, inputs[channel] + offset);
            plugin->connect_port(limiter, CHANNELS + channel, outputs[channel] + offset);
        }
        plugin->run(limiter, count);
    }

    float ceiling = powf(10.0f, ceiling_db / 20.0f);
    float maximum = 0.0f;
    int linked_attenuation_seen = 0;
    for (unsigned long channel = 0; channel < CHANNELS; channel++) {
        for (unsigned long frame = 0; frame < SAMPLE_COUNT; frame++) {
            float magnitude = fabsf(outputs[channel][frame]);
            if (magnitude > maximum) maximum = magnitude;
            if (magnitude > ceiling + 0.0001f) {
                fprintf(stderr, "ceiling exceeded: channel %lu frame %lu value %.7f ceiling %.7f\n",
                        channel, frame, magnitude, ceiling);
                plugin->cleanup(limiter);
                return 2;
            }
        }
    }
    for (unsigned long frame = 2240; frame < 5200; frame++) {
        if (fabsf(outputs[1][frame]) < 0.30f) linked_attenuation_seen = 1;
    }
    if (!linked_attenuation_seen) {
        fputs("linked stereo attenuation was not observed\n", stderr);
        plugin->cleanup(limiter);
        return 3;
    }

    printf("ceiling=%.6f max=%.6f linked=ok latency_frames=%lu\n", ceiling, maximum,
           (unsigned long)lrintf((float)SAMPLE_RATE * lookahead_ms / 1000.0f));
    plugin->cleanup(limiter);
    return 0;
}
