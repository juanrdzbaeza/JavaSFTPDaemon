// java
package org.juanrdzbaeza.daemon;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LocalWatcher {
    private final Path dir;
    private final SftpSyncService sftp;
    private final SyncState state;
    private WatchService watcher;
    private volatile boolean running = true;

    // evita subir la misma ruta en paralelo
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
        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

        while (running) {
            WatchKey key = watcher.take();
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    System.out.println("WatchService overflow event");
                    continue;
                }

                Path rel = (Path) ev.context();
                Path full = dir.resolve(rel).toAbsolutePath();
                System.out.println("Local change detected: " + ev.kind() + " -> " + full);

                // ignorar ficheros temporales comunes
                if (isTemporaryFile(full)) continue;

                // si fue descargado recientemente por el sync remoto, ignorar para evitar bucle
                if (state.isRecentlyDownloaded(full)) {
                    System.out.println("Ignorado (reciente descarga remota): " + full);
                    continue;
                }

                // asegurar que es un fichero regular y esperar a que termine de escribirse
                if (!Files.exists(full) || !Files.isRegularFile(full)) continue;
                if (!waitForStableFile(full)) {
                    System.out.println("Archivo no estable, se omite: " + full);
                    continue;
                }

                try {
                    long lastMod = Files.getLastModifiedTime(full).toMillis();
                    Long prev = state.getLastUploaded(full);
                    if (prev != null && prev >= lastMod) {
                        // ya subido esa versión o más nueva
                        continue;
                    }

                    String keyStr = full.toString();
                    // evitar subidas concurrentes de la misma ruta
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
}
