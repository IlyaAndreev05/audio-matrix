#include "ladspa_abi.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CHANNELS 8
#define PORTS (CHANNELS * 2 + 3)
#define LIMITER_ID 4931801UL
#define MAX_LOOKAHEAD_MS 10.0f

typedef struct {
    unsigned long sample_rate;
    uint64_t frame;
    uint64_t deque_head;
    uint64_t deque_tail;
    uint64_t *deque_frames;
    float *deque_peaks;
    float *delay;
    LADSPA_Data *ports[PORTS];
    unsigned long capacity;
    unsigned long lookahead;
    float gain;
} Limiter;

static const LADSPA_PortDescriptor port_descriptors[PORTS] = {
    LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO, LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO,
    LADSPA_PORT_INPUT | LADSPA_PORT_CONTROL,
    LADSPA_PORT_INPUT | LADSPA_PORT_CONTROL,
    LADSPA_PORT_INPUT | LADSPA_PORT_CONTROL
};

static const char *port_names[PORTS] = {
    "Input 1", "Input 2", "Input 3", "Input 4", "Input 5", "Input 6", "Input 7", "Input 8",
    "Output 1", "Output 2", "Output 3", "Output 4", "Output 5", "Output 6", "Output 7", "Output 8",
    "Ceiling (dBFS)", "Lookahead (ms)", "Release (ms)"
};

static const LADSPA_PortRangeHint range_hints[PORTS] = {
    {0}, {0}, {0}, {0}, {0}, {0}, {0}, {0},
    {0}, {0}, {0}, {0}, {0}, {0}, {0}, {0},
    {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE, -12.0f, 0.0f},
    {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE, 1.0f, MAX_LOOKAHEAD_MS},
    {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE, 5.0f, 500.0f}
};

static void reset(Limiter *limiter) {
    limiter->frame = 0;
    limiter->deque_head = 0;
    limiter->deque_tail = 0;
    limiter->gain = 1.0f;
    memset(limiter->delay, 0, sizeof(float) * CHANNELS * limiter->capacity);
    memset(limiter->deque_frames, 0, sizeof(uint64_t) * limiter->capacity);
    memset(limiter->deque_peaks, 0, sizeof(float) * limiter->capacity);
}

static LADSPA_Handle instantiate(const LADSPA_Descriptor *descriptor, unsigned long sample_rate) {
    (void)descriptor;
    Limiter *limiter = (Limiter *)calloc(1, sizeof(Limiter));
    if (limiter == NULL) return NULL;
    limiter->sample_rate = sample_rate == 0 ? 48000 : sample_rate;
    limiter->capacity = (unsigned long)ceilf((float)limiter->sample_rate * MAX_LOOKAHEAD_MS / 1000.0f) + 2;
    limiter->delay = (float *)calloc(CHANNELS * limiter->capacity, sizeof(float));
    limiter->deque_frames = (uint64_t *)calloc(limiter->capacity, sizeof(uint64_t));
    limiter->deque_peaks = (float *)calloc(limiter->capacity, sizeof(float));
    if (limiter->delay == NULL || limiter->deque_frames == NULL || limiter->deque_peaks == NULL) {
        free(limiter->delay);
        free(limiter->deque_frames);
        free(limiter->deque_peaks);
        free(limiter);
        return NULL;
    }
    reset(limiter);
    return (LADSPA_Handle)limiter;
}

static void connect_port(LADSPA_Handle instance, unsigned long port, LADSPA_Data *data) {
    Limiter *limiter = (Limiter *)instance;
    if (port < PORTS) limiter->ports[port] = data;
}

static void activate(LADSPA_Handle instance) { reset((Limiter *)instance); }

static float control_value(const Limiter *limiter, unsigned long port, float fallback, float low, float high) {
    LADSPA_Data *value = limiter->ports[port];
    float result = value == NULL ? fallback : *value;
    if (!isfinite(result)) result = fallback;
    if (result < low) result = low;
    if (result > high) result = high;
    return result;
}

static void run(LADSPA_Handle instance, unsigned long sample_count) {
    Limiter *limiter = (Limiter *)instance;
    float ceiling_db = control_value(limiter, CHANNELS * 2, -1.0f, -12.0f, 0.0f);
    float lookahead_ms = control_value(limiter, CHANNELS * 2 + 1, 5.0f, 1.0f, MAX_LOOKAHEAD_MS);
    float release_ms = control_value(limiter, CHANNELS * 2 + 2, 50.0f, 5.0f, 500.0f);
    unsigned long lookahead = (unsigned long)lrintf((float)limiter->sample_rate * lookahead_ms / 1000.0f);
    if (lookahead >= limiter->capacity) lookahead = limiter->capacity - 1;
    if (lookahead != limiter->lookahead) {
        limiter->lookahead = lookahead;
        reset(limiter);
    }
    const float ceiling = powf(10.0f, ceiling_db / 20.0f);
    const float release_coefficient = expf(-1.0f / (release_ms * 0.001f * (float)limiter->sample_rate));

    for (unsigned long sample = 0; sample < sample_count; sample++, limiter->frame++) {
        const unsigned long write_slot = (unsigned long)(limiter->frame % limiter->capacity);
        float peak = 0.0f;
        for (unsigned long channel = 0; channel < CHANNELS; channel++) {
            LADSPA_Data *input = limiter->ports[channel];
            float value = input == NULL ? 0.0f : input[sample];
            limiter->delay[channel * limiter->capacity + write_slot] = value;
            float magnitude = fabsf(value);
            if (magnitude > peak) peak = magnitude;
        }

        while (limiter->deque_tail > limiter->deque_head) {
            unsigned long tail_slot = (unsigned long)((limiter->deque_tail - 1) % limiter->capacity);
            if (limiter->deque_peaks[tail_slot] > peak) break;
            limiter->deque_tail--;
        }
        unsigned long add_slot = (unsigned long)(limiter->deque_tail % limiter->capacity);
        limiter->deque_frames[add_slot] = limiter->frame;
        limiter->deque_peaks[add_slot] = peak;
        limiter->deque_tail++;
        while (limiter->deque_tail > limiter->deque_head) {
            unsigned long head_slot = (unsigned long)(limiter->deque_head % limiter->capacity);
            if (limiter->deque_frames[head_slot] + lookahead >= limiter->frame) break;
            limiter->deque_head++;
        }

        float window_peak = 0.0f;
        if (limiter->deque_tail > limiter->deque_head)
            window_peak = limiter->deque_peaks[(unsigned long)(limiter->deque_head % limiter->capacity)];
        float target_gain = window_peak > ceiling ? ceiling / window_peak : 1.0f;
        if (target_gain < limiter->gain) limiter->gain = target_gain;
        else limiter->gain = target_gain + (limiter->gain - target_gain) * release_coefficient;

        long read_frame = (long)limiter->frame - (long)lookahead;
        if (read_frame < 0) {
            for (unsigned long channel = 0; channel < CHANNELS; channel++) {
                LADSPA_Data *output = limiter->ports[CHANNELS + channel];
                if (output != NULL) output[sample] = 0.0f;
            }
            continue;
        }
        unsigned long read_slot = (unsigned long)((uint64_t)read_frame % limiter->capacity);
        for (unsigned long channel = 0; channel < CHANNELS; channel++) {
            LADSPA_Data *output = limiter->ports[CHANNELS + channel];
            if (output != NULL) output[sample] = limiter->delay[channel * limiter->capacity + read_slot] * limiter->gain;
        }
    }
}

static void cleanup(LADSPA_Handle instance) {
    Limiter *limiter = (Limiter *)instance;
    if (limiter == NULL) return;
    free(limiter->delay);
    free(limiter->deque_frames);
    free(limiter->deque_peaks);
    free(limiter);
}

static const LADSPA_Descriptor descriptor = {
    .UniqueID = LIMITER_ID,
    .Label = "audiomatrix_limiter",
    .Properties = LADSPA_PROPERTY_HARD_RT_CAPABLE,
    .Name = "Audio Matrix Lookahead Limiter",
    .Maker = "IlyaAndreev05",
    .Copyright = "MIT",
    .PortCount = PORTS,
    .PortDescriptors = port_descriptors,
    .PortNames = port_names,
    .PortRangeHints = range_hints,
    .ImplementationData = NULL,
    .instantiate = instantiate,
    .connect_port = connect_port,
    .activate = activate,
    .run = run,
    .run_adding = NULL,
    .set_run_adding_gain = NULL,
    .deactivate = NULL,
    .cleanup = cleanup
};

const LADSPA_Descriptor *ladspa_descriptor(unsigned long index) {
    return index == 0 ? &descriptor : NULL;
}
