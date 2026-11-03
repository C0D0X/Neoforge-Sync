# NeoForge Sync

Work in progress. This project does not yet synchronize files and must not be
installed as a working syncer.

Target: Minecraft 1.21.1, NeoForge 21.1.259, TNP Limitless 8 1.78.1.
Java 21 JDK is required to build with `gradlew build`.

The intended publisher discovers the baseline at `stock/` inside the publisher
instance. The current `stock/` is a CurseForge export: `manifest.json` lists
mod project and file IDs, and `overrides/` contains pack-provided files. It is
not a complete installed instance: most mod jars are not present in the export.
The publisher must resolve the stock mod list reliably before it can compare
mods; it must not treat all unrecognized jars as additions or publish them by
default. Baseline and active pack versions must agree before publication.

For explicitly selected non-mod files, the intended comparison uses
`stock/overrides/` as the baseline. The scanner must exclude `stock/`, local
sync state, credentials, and personal or generated files. A read-only stock mod
comparison runs read-only at client startup if `stock/manifest.json` exists;
results are written to the game log. It does not publish or apply any files.
Config synchronization is out of scope for now. A read-only config inventory
prototype is present but is not connected to startup or publishing. A manifest
parser, GitHub release transport, publisher and receiver coordinators, verified
staging, managed state, sync planner, and a journaled post-exit helper are present.
The helper can roll back an interrupted process transaction, but has not been
validated against power loss. Client startup now selects publisher, receiver, or
recovery mode and offers a shutdown notice for staged updates. This startup
integration has not yet been validated in a real CurseForge launch. Self-update
is implemented by copying the helper JAR before replacing the installed updater;
it has only been exercised in isolated tests. Do not install or distribute the
JAR as a working syncer yet.

For a read-only diagnostic of the stock-mod mapping, run in PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\inspect-stock.ps1 -Instance 'C:\path\to\instance'
```

This only compares IDs and filenames. It does not verify file content, detect
all forms of ambiguity, or classify unidentified JARs as publishable mods.
