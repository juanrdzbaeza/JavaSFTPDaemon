// java
package org.juanrdzbaeza.daemon;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado compartido entre SftpSyncService y LocalWatcher.
 * Añadidos métodos para consultar timestamps de descargas.
 */
public class SyncState {
    private final Map<String, Long> lastDownloaded = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUploaded = new ConcurrentHashMap<>();
    private final long recentDownloadWindowMs;

    public SyncState(long recentDownloadWindowMs) {
        this.recentDownloadWindowMs = recentDownloadWindowMs;
    }

    public SyncState() {
        this(3000L);
    }

    public void markDownloaded(Path p) {
        if (p == null) return;
        lastDownloaded.put(p.toAbsolutePath().toString(), System.currentTimeMillis());
    }

    public boolean isRecentlyDownloaded(Path p) {
        if (p == null) return false;
        Long t = lastDownloaded.get(p.toAbsolutePath().toString());
        return t != null && (System.currentTimeMillis() - t) < recentDownloadWindowMs;
    }

    /**
     * Devuelve el timestamp (ms) de la última descarga registrada para la ruta, o null si no existe.
     */
    public Long getDownloadedTimestamp(Path p) {
        if (p == null) return null;
        return lastDownloaded.get(p.toAbsolutePath().toString());
    }

    public void removeDownloaded(Path p) {
        if (p == null) return;
        lastDownloaded.remove(p.toAbsolutePath().toString());
    }

    public void markUploaded(Path p, long lastModifiedMillis) {
        if (p == null) return;
        lastUploaded.put(p.toAbsolutePath().toString(), lastModifiedMillis);
    }

    public Long getLastUploaded(Path p) {
        if (p == null) return null;
        return lastUploaded.get(p.toAbsolutePath().toString());
    }

    public void removeUploaded(Path p) {
        if (p == null) return;
        lastUploaded.remove(p.toAbsolutePath().toString());
    }
}
