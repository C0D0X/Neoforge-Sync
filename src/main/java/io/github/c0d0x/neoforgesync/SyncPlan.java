package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Computes an update without modifying existing files. */
public record SyncPlan(Set<String> install, Set<String> remove) {
    public SyncPlan {
        install = Set.copyOf(install);
        remove = Set.copyOf(remove);
    }

    public static SyncPlan calculate(Map<String, String> previouslyManaged, Map<String, String> current,
                                     SyncManifest manifest) throws IOException {
        Set<String> existing = new HashSet<>();
        for (String name : current.keySet()) {
            if (!existing.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Ambiguous installed filenames");
        }
        Set<String> oldNames = new HashSet<>();
        for (var old : previouslyManaged.entrySet()) {
            if (!oldNames.add(old.getKey().toLowerCase(Locale.ROOT))) throw new IOException("Ambiguous managed filenames");
            if (current.containsKey(old.getKey()) && !old.getValue().equals(current.get(old.getKey()))) {
                throw new IOException("Managed mod modified outside syncer: " + old.getKey());
            }
        }
        Set<String> install = new TreeSet<>();
        Set<String> wanted = new HashSet<>();
        for (var file : manifest.mods()) {
            String name = file.name();
            if (!wanted.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Ambiguous manifest filenames");
            if (existing.contains(name.toLowerCase(Locale.ROOT)) && !current.containsKey(name)) {
                throw new IOException("Filename differs only in case: " + name);
            }
            if (current.containsKey(name) && !previouslyManaged.containsKey(name)) {
                throw new IOException("New sync file conflicts with unmanaged mod: " + name);
            }
            if (!file.sha256().equals(current.get(name))) install.add(name);
        }
        Set<String> remove = new TreeSet<>();
        for (String name : previouslyManaged.keySet()) {
            if (!wanted.contains(name.toLowerCase(Locale.ROOT)) && current.containsKey(name)) remove.add(name);
        }
        return new SyncPlan(install, remove);
    }
}
