package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/** Maps the shared updater asset to this installation's existing updater filename. */
public final class SelfUpdate {
    private SelfUpdate() {}

    public static SyncManifest effective(SyncManifest manifest, String ownJar) throws IOException {
        if (manifest.updater() == null) return manifest;
        if (ownJar == null || !ownJar.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || ownJar.contains("..")) {
            throw new IOException("Cannot identify installed updater JAR");
        }
        for (var mod : manifest.mods()) {
            if (mod.name().toLowerCase(Locale.ROOT).equals(ownJar.toLowerCase(Locale.ROOT))) {
                throw new IOException("Shared mod conflicts with installed updater");
            }
        }
        var files = new ArrayList<>(manifest.mods());
        var updater = manifest.updater();
        files.add(new SyncManifest.ModFile(ownJar, updater.sha256(), updater.size(), updater.url()));
        return new SyncManifest(manifest.packVersion(), files);
    }
}
