package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Starts the bundled helper using the running Java and Gson locations. */
public final class HelperLauncher {
    private HelperLauncher() {}

    public static Process launch(Path instance, String stageName) throws IOException {
        ProcessHandle game = ProcessHandle.current();
        long start = game.info().startInstant().orElseThrow(() -> new IOException("Cannot determine game start time")).toEpochMilli();
        return launch(instance, stageName, game.pid(), start);
    }

    static Process launch(Path instance, String stageName, long pid, long start) throws IOException {
        if (pid <= 0 || start <= 0 || (!stageName.equals("recover") && !stageName.matches("stage-[A-Za-z0-9_-]{1,100}"))) {
            throw new IOException("Invalid helper arguments");
        }
        Path work = instance.resolve(".neoforgesync");
        if (Files.isSymbolicLink(work) || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid helper directory");
        Path log = work.resolve("helper.log");
        if (Files.isSymbolicLink(log)) throw new IOException("Helper log is a symbolic link");
        Path program = codeLocation(PostExitHelper.class);
        Path gson = codeLocation(com.google.gson.JsonParser.class);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String ownJar = "none";
        Path helperCopy = null;
        try {
            if (Files.isRegularFile(program, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isSameFile(program.getParent(), instance.resolve("mods"))) throw new IOException("Syncer JAR is outside this instance's mods folder");
                ownJar = program.getFileName().toString();
                helperCopy = Files.createTempFile(work, "helper-", ".jar");
                Files.copy(program, helperCopy, StandardCopyOption.REPLACE_EXISTING);
                if (Files.mismatch(program, helperCopy) != -1) throw new IOException("Syncer changed while copying the helper");
                program = helperCopy;
            }
            String classpath = program + System.getProperty("path.separator") + gson;
            return new ProcessBuilder(java.toString(), "-cp", classpath, PostExitHelper.class.getName(),
                    instance.toAbsolutePath().toString(), stageName, Long.toString(pid), Long.toString(start), ownJar)
                    .directory(instance.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        } catch (IOException | RuntimeException e) {
            if (helperCopy != null) try { Files.deleteIfExists(helperCopy); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    private static Path codeLocation(Class<?> type) throws IOException {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if ((source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file"))
                    && type == PostExitHelper.class) {
                var info = net.neoforged.fml.ModList.get().getModFileById(NeoForgeSync.MOD_ID);
                if (info != null && Files.isRegularFile(info.getFile().getFilePath())) return info.getFile().getFilePath();
            }
            if (source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file")) {
                throw new IOException("Helper class location is not a local file: " + type.getName());
            }
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | SecurityException e) {
            throw new IOException("Cannot locate helper dependencies", e);
        }
    }
}
