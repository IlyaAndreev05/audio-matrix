package io.github.ilyaandreev05.audiomatrix.audio;

/** Stream-only WirePlumber operations. The backend invokes these inside its shared controls lane. */
final class PipeWireStreamControls {
    @FunctionalInterface interface VolumeWriter { void set(int nodeId, double linearVolume) throws Exception; }
    @FunctionalInterface interface MuteWriter { void set(int nodeId, boolean muted) throws Exception; }
    private final VolumeWriter volume;
    private final MuteWriter mute;

    PipeWireStreamControls(VolumeWriter volume, MuteWriter mute) {
        this.volume = java.util.Objects.requireNonNull(volume);
        this.mute = java.util.Objects.requireNonNull(mute);
    }

    void volume(PipeWireBackend.Snapshot live, int nodeId, double level) throws Exception {
        requireStream(live, nodeId);
        volume.set(nodeId, PipeWireBackend.clamp(level, 0, 1));
    }

    void mute(PipeWireBackend.Snapshot live, int nodeId, boolean muted) throws Exception {
        requireStream(live, nodeId);
        mute.set(nodeId, muted);
    }

    private static void requireStream(PipeWireBackend.Snapshot live, int id) {
        if (!live.connected() || live.streams().stream().noneMatch(node -> node.id() == id))
            throw new IllegalArgumentException("Playback stream is unavailable: " + id);
    }
}
