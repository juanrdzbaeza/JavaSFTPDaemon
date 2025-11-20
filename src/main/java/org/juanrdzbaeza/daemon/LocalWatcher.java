// java
package org.juanrdzbaeza.daemon;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LocalWatcher {
    private final Path dir;
    private final SftpSyncService sftp;
    private final SyncState state;
    private WatchService watcher;
    private volatile boolean running = true;

    private final Set<String> uploading = ConcurrentHashMap.newKeySet();

    private static final long STABLE_WAIT_MS = 300;
    private static final long STABLE_MAX_MS = 5_000;

    public LocalWatcher(Path dir, SftpSyncService sftp, SyncState state) {
        this.dir = dir;
        this.sftp = sftp;
        this.state = state;
    }

    public void start() throws IOException, InterruptedException {
        watcher = FileSystems.getDefault().newWatchService();
        // registrar recursivamente todos los subdirectorios
        registerAll(dir);

        while (running) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (ClosedWatchServiceException cwse) {
                // El WatchService fue cerrado (probablemente por stop()).
                // Salir del bucle de forma silenciosa para evitar stacktrace.
                break;
            }
            Path watchDir = (Path) key.watchable();
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    System.out.println("WatchService overflow event");
                    continue;
                }

                Path rel = (Path) ev.context();
                Path full = watchDir.resolve(rel).toAbsolutePath();
                System.out.println("Local change detected: " + ev.kind() + " -> " + full);

                if (isTemporaryFile(full)) continue;

                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    // si es directorio creado, registrarlo para vigilar recursivamente
                    if (Files.isDirectory(full)) {
                        try {
                            registerAll(full);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        // no subir directorio en sí
                        continue;
                    }
                }

                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    // borrar remoto (archivo o directorio)
                    try {
                        sftp.deleteRemote(full);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    continue;
                }

                // ENTRY_CREATE / ENTRY_MODIFY (archivo)
                if (state.isRecentlyDownloaded(full)) {
                    System.out.println("Ignorado (reciente descarga remota): " + full);
                    continue;
                }

                if (!Files.exists(full) || !Files.isRegularFile(full)) continue;
                if (!waitForStableFile(full)) {
                    System.out.println("Archivo no estable, se omite: " + full);
                    continue;
                }

                try {
                    long lastMod = Files.getLastModifiedTime(full).toMillis();
                    Long prev = state.getLastUploaded(full);
                    if (prev != null && prev >= lastMod) {
                        continue;
                    }

                    String keyStr = full.toString();
                    if (!uploading.add(keyStr)) {
                        System.out.println("Ya en subida: " + full);
                        continue;
                    }

                    try {
                        sftp.uploadFile(full);
                        state.markUploaded(full, lastMod);
                    } finally {
                        uploading.remove(keyStr);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (!key.reset()) {
                System.out.println("WatchKey no válido, saliendo del watcher");
                break;
            }
        }
    }

    public void stop() {
        running = false;
        try { if (watcher != null) watcher.close(); } catch (IOException ignored) {}
    }

    private boolean isTemporaryFile(Path p) {
        String name = p.getFileName() != null ? p.getFileName().toString() : "";
        if (name.startsWith(".")) return true;
        String lower = name.toLowerCase();
        return lower.endsWith(".tmp") || lower.endsWith(".part") || lower.endsWith(".swp");
    }

    private boolean waitForStableFile(Path p) throws InterruptedException {
        long start = System.currentTimeMillis();
        try {
            long previousSize = -1;
            while (System.currentTimeMillis() - start < STABLE_MAX_MS) {
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    return false;
                }

                if (size == previousSize) {
                    TimeUnit.MILLISECONDS.sleep(STABLE_WAIT_MS);
                    long size2 = Files.size(p);
                    if (size2 == size) return true;
                    previousSize = size2;
                } else {
                    previousSize = size;
                    TimeUnit.MILLISECONDS.sleep(STABLE_WAIT_MS);
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    // registra un directorio y todos sus subdirectorios en el WatchService
    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new java.util.HashSet<>(), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dirPath, BasicFileAttributes attrs) throws IOException {
                dirPath.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
