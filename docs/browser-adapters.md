# Browser audio controls

Audio Matrix always discovers Chrome and Firefox playback as ordinary PipeWire application streams. It shows a separate stream slider only when PipeWire exposes an independently controllable stream.

Chrome's optional tab-capture extension can create a separate stream for a selected tab. The browser requires a user gesture to begin capture; Audio Matrix does not bypass that requirement. The Chrome extension and native host are included in the Debian package.

Firefox exposes tab titles and mute controls to browser extensions, but it does not expose per-tab PCM volume. The included toolbar add-on can focus and mute tabs inside Firefox; that toolbar add-on is not connected to the Audio Matrix GNOME menu. Audio Matrix therefore shows per-tab volume only when the system itself exposes multiple independent Firefox streams. The packaged XPI is unsigned; it is suitable for temporary developer loading, while persistent distribution requires a signed Firefox add-on.
