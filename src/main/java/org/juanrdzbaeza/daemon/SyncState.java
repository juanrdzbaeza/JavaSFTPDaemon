// java
package org.juanrdzbaeza.daemon;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado compartido entre SftpSyncService y LocalWatcher para:
 * - marcar archivos descargados recientemente (evitar re-subida inmediata)
 * - llevar el mapa de últimas subidas (evitar re-subidas iguales)
 */
public class SyncState {
    private final Map<String, Long> lastDownloaded = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUploaded = new ConcurrentHashMap<>();

    // ventana en ms durante la que un cambio local se considera provocado por una descarga remota
    private final long recentDownloadWindowMs;

    public SyncState(long recentDownloadWindowMs) {
        this.recentDownloadWindowMs = recentDownloadWindowMs;
    }

    public SyncState() {
        this(3000L); // 3s por defecto
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

    public void markUploaded(Path p, long lastModifiedMillis) {
        if (p == null) return;
        lastUploaded.put(p.toAbsolutePath().toString(), lastModifiedMillis);
    }

    public Long getLastUploaded(Path p) {
        if (p == null) return null;
        return lastUploaded.get(p.toAbsolutePath().toString());
    }
}
