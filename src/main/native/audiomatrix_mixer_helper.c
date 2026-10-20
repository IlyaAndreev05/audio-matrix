#define _DEFAULT_SOURCE
#include <errno.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

typedef struct _WpCore WpCore;
typedef struct _WpPlugin WpPlugin;
typedef struct _GAsyncResult GAsyncResult;
typedef struct _GMainLoop GMainLoop;
typedef struct _GVariant GVariant;
typedef struct _GVariantType GVariantType;
typedef struct _GVariantBuilder GVariantBuilder;
typedef struct _GError GError;
typedef struct _GCancellable GCancellable;
typedef void (*GAsyncReadyCallback)(void *, GAsyncResult *, void *);
typedef void *gpointer;
typedef int gboolean;
typedef unsigned int guint;

extern void wp_init(guint flags);
extern WpCore *wp_core_new(void *context, void *conf, void *properties);
extern gboolean wp_core_connect(WpCore *core);
extern gboolean wp_core_is_connected(WpCore *core);
extern void wp_core_load_component(WpCore *core, const char *component, const char *type,
                                   void *args, const char *provides, GCancellable *cancellable,
                                   GAsyncReadyCallback callback, gpointer data);
extern gboolean wp_core_load_component_finish(WpCore *core, GAsyncResult *result, GError **error);
extern gboolean wp_core_sync(WpCore *core, GCancellable *cancellable,
                             GAsyncReadyCallback callback, gpointer data);
extern gboolean wp_core_sync_finish(WpCore *core, GAsyncResult *result, GError **error);
extern WpPlugin *wp_plugin_find(WpCore *core, const char *name);
extern void wp_object_activate(void *object, guint features, GCancellable *cancellable,
                               GAsyncReadyCallback callback, gpointer data);
extern gboolean wp_object_activate_finish(void *object, GAsyncResult *result, GError **error);
extern GMainLoop *g_main_loop_new(void *context, gboolean running);
extern void g_main_loop_run(GMainLoop *loop);
extern void g_main_loop_quit(GMainLoop *loop);
extern void g_main_loop_unref(GMainLoop *loop);
extern gboolean g_main_context_iteration(void *context, gboolean may_block);
extern void g_object_unref(void *object);
extern void g_signal_emit_by_name(void *object, const char *name, ...);
extern GVariant *g_variant_new_double(double value);
extern GVariant *g_variant_new_boolean(gboolean value);
extern GVariant *g_variant_ref_sink(GVariant *value);
extern void g_variant_unref(GVariant *value);
extern GVariantType *g_variant_type_new(const char *type_string);
extern void g_variant_type_free(GVariantType *type);
extern GVariantBuilder *g_variant_builder_new(const GVariantType *type);
extern gboolean g_variant_builder_add(GVariantBuilder *builder, const char *format, ...);
extern GVariant *g_variant_builder_end(GVariantBuilder *builder);
extern void g_variant_builder_unref(GVariantBuilder *builder);
extern gboolean g_variant_lookup(GVariant *dictionary, const char *key, const char *format, ...);
extern void g_error_free(GError *error);

static WpCore *core;
static WpPlugin *mixer;
static int initialization_ok;
struct remembered_level { unsigned int id; double volume; int valid; };
static struct remembered_level remembered_levels[512];

struct wait_result { GMainLoop *loop; int ok; };

static void quit_with_result(struct wait_result *wait, int ok) {
    wait->ok = ok;
    g_main_loop_quit(wait->loop);
}

static void on_loaded(void *loaded_core, GAsyncResult *result, void *data) {
    struct wait_result *wait = data;
    GError *error = NULL;
    int ok = wp_core_load_component_finish(loaded_core, result, &error);
    if (error) g_error_free(error);
    quit_with_result(wait, ok);
}

static void on_activated(void *object, GAsyncResult *result, void *data) {
    struct wait_result *wait = data;
    GError *error = NULL;
    int ok = wp_object_activate_finish(object, result, &error);
    if (error) g_error_free(error);
    quit_with_result(wait, ok);
}

static void on_sync(void *sync_core, GAsyncResult *result, void *data) {
    struct wait_result *wait = data;
    GError *error = NULL;
    int ok = wp_core_sync_finish(sync_core, result, &error);
    if (error) g_error_free(error);
    quit_with_result(wait, ok);
}

static int wait_for_core_connection(void) {
    for (int attempt = 0; attempt < 300 && !wp_core_is_connected(core); attempt++) {
        g_main_context_iteration(NULL, 0);
        usleep(10000);
    }
    return wp_core_is_connected(core);
}

static int initialize(void) {
    wp_init(1);
    core = wp_core_new(NULL, NULL, NULL);
    if (!core || !wp_core_connect(core) || !wait_for_core_connection()) return 0;

    struct wait_result loaded = { g_main_loop_new(NULL, 0), 0 };
    wp_core_load_component(core, "libwireplumber-module-mixer-api", "module", NULL,
                           NULL, NULL, on_loaded, &loaded);
    g_main_loop_run(loaded.loop);
    g_main_loop_unref(loaded.loop);
    if (!loaded.ok) return 0;

    mixer = wp_plugin_find(core, "mixer-api");
    if (!mixer) return 0;
    struct wait_result activated = { g_main_loop_new(NULL, 0), 0 };
    wp_object_activate(mixer, 1, NULL, on_activated, &activated);
    g_main_loop_run(activated.loop);
    g_main_loop_unref(activated.loop);
    initialization_ok = activated.ok;
    return initialization_ok;
}

static int synchronize(void) {
    struct wait_result wait = { g_main_loop_new(NULL, 0), 0 };
    if (!wp_core_sync(core, NULL, on_sync, &wait)) {
        g_main_loop_unref(wait.loop);
        return 0;
    }
    g_main_loop_run(wait.loop);
    g_main_loop_unref(wait.loop);
    return wait.ok;
}

static void remember_level(unsigned int id, double volume);

static int set_volume(unsigned int id, double volume) {
    if (!isfinite(volume) || volume < 0.0 || volume > 1.0) return 0;
    GVariant *value = g_variant_ref_sink(g_variant_new_double(volume));
    gboolean accepted = 0;
    g_signal_emit_by_name(mixer, "set-volume", id, value, &accepted);
    g_variant_unref(value);
    if (!accepted || !synchronize()) return 0;
    remember_level(id, volume);
    return 1;
}

static int read_level(unsigned int id, double *volume, int *muted) {
    GVariant *state = NULL;
    g_signal_emit_by_name(mixer, "get-volume", id, &state);
    if (!state) return 0;
    gboolean has_volume = g_variant_lookup(state, "volume", "d", volume);
    gboolean has_mute = g_variant_lookup(state, "mute", "b", muted);
    g_variant_unref(state);
    return has_volume && has_mute && isfinite(*volume) && *volume >= 0.0 && *volume <= 1.0;
}

static double remembered_level(unsigned int id) {
    struct remembered_level *slot = &remembered_levels[id % 512];
    return slot->valid && slot->id == id ? slot->volume : -1.0;
}

static void remember_level(unsigned int id, double volume) {
    if (volume < 0.0 || volume > 1.0 || !isfinite(volume)) return;
    remembered_levels[id % 512] = (struct remembered_level) { id, volume, 1 };
}

static int set_volume_and_mute(unsigned int id, double volume, int muted, int sync) {
    GVariantType *type = g_variant_type_new("a{sv}");
    GVariantBuilder *builder = g_variant_builder_new(type);
    g_variant_type_free(type);
    g_variant_builder_add(builder, "{sv}", "volume", g_variant_new_double(volume));
    g_variant_builder_add(builder, "{sv}", "mute", g_variant_new_boolean(muted));
    GVariant *value = g_variant_ref_sink(g_variant_builder_end(builder));
    g_variant_builder_unref(builder);
    gboolean accepted = 0;
    g_signal_emit_by_name(mixer, "set-volume", id, value, &accepted);
    g_variant_unref(value);
    return accepted && (!sync || synchronize());
}

static void short_pause(void) {
    struct timespec pause = { .tv_sec = 0, .tv_nsec = 1000000 };
    nanosleep(&pause, NULL);
    g_main_context_iteration(NULL, 0);
}

static int set_mute(unsigned int id, int muted, double saved_level) {
    double current = 0.0;
    int currently_muted = 0;
    if (!read_level(id, &current, &currently_muted)) return 0;
    if (saved_level >= 0.0 && saved_level <= 1.0) remember_level(id, saved_level);
    else if (remembered_level(id) < 0.0) remember_level(id, current);
    if (muted == currently_muted) return 1;

    double target = current;
    if (!muted) {
        double saved = remembered_level(id);
        if (saved >= 0.0) target = saved;
        if (!set_volume_and_mute(id, 0.0, 0, 0)) return 0;
        for (int step = 1; step <= 5; step++) {
            short_pause();
            if (!set_volume_and_mute(id, target * step / 5.0, 0, step == 5)) return 0;
        }
        return 1;
    }

    for (int step = 1; step <= 5; step++) {
        short_pause();
        double level = current * (5 - step) / 5.0;
        if (!set_volume_and_mute(id, level, step == 5, step == 5)) return 0;
    }
    return 1;
}

int main(void) {
    if (!initialize()) {
        fputs("WirePlumber Mixer API could not be initialized\n", stderr);
        return 2;
    }
    puts("READY");
    fflush(stdout);
    char line[256];
    while (fgets(line, sizeof(line), stdin)) {
        if (!strncmp(line, "Q", 1)) break;
        unsigned int id = 0;
        double volume = 0.0;
        int muted = 0;
        int ok = 0;
        if (sscanf(line, "V %u %lf", &id, &volume) == 2) ok = set_volume(id, volume);
        else {
            double saved_level = -1.0;
            int fields = sscanf(line, "M %u %d %lf", &id, &muted, &saved_level);
            if (fields >= 2 && (muted == 0 || muted == 1)
                    && (fields == 2 || (isfinite(saved_level) && saved_level >= -1.0 && saved_level <= 1.0)))
                ok = set_mute(id, muted, saved_level);
        }
        puts(ok ? "OK" : "ERR runtime volume update failed");
        fflush(stdout);
    }
    if (mixer) g_object_unref(mixer);
    if (core) g_object_unref(core);
    return 0;
}
