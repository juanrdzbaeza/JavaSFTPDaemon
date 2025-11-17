// java
package org.juanrdzbaeza.daemon;

import java.io.IOException;
import java.nio.file.*;

public class LocalWatcher {
    private final Path dir;
    private final FtpSyncService ftp;
    private WatchService watcher;
    private volatile boolean running = true;

    public LocalWatcher(Path dir, FtpSyncService ftp) {
        this.dir = dir;
        this.ftp = ftp;
    }

    public void start() throws IOException, InterruptedException {
        watcher = FileSystems.getDefault().newWatchService();
        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        while (running) {
            WatchKey key = watcher.take();
            for (WatchEvent<?> ev : key.pollEvents()) {
                Path rel = (Path) ev.context();
                Path full = dir.resolve(rel);
                System.out.println("Local change detected: " + ev.kind() + " -> " + full);
                // subir fichero al FTP (simple)
                if (Files.isRegularFile(full)) {
                    ftp.uploadFile(full);
                }
            }
            key.reset();
        }
    }

    public void stop() {
        running = false;
        try { if (watcher != null) watcher.close(); } catch (IOException ignored) {}
    }
}
