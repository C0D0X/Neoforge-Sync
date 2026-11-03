package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HelperLauncherTest {
    @TempDir Path root;

    @Test void resolvesNeoForgeUnionJarWithSpaces() throws Exception {
        Path jar = Files.writeString(root.resolve("gson with spaces.jar"), "data");
        String location = "union:" + jar.toUri().getRawPath() + "%23131!/";
        assertEquals(jar.toRealPath(), HelperLauncher.unionJarPath(location).toRealPath());
    }

    @Test void rejectsUnexpectedUnionLocations() {
        assertThrows(IOException.class, () -> HelperLauncher.unionJarPath("union:/nonexistent.jar%231!/"));
        assertThrows(IOException.class, () -> HelperLauncher.unionJarPath("union:/tmp/not-a-jar%231!/"));
        assertThrows(IOException.class, () -> HelperLauncher.unionJarPath("https://example.com/library.jar"));
    }
}
