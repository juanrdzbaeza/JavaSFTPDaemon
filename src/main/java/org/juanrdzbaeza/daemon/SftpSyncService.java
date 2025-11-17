// java
package org.juanrdzbaeza.daemon;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SftpSyncService completo: mantiene conexión perezosa, soporta sync recursivo remoto->local,
 * upload/delete remotas y sincronización periódica vía startPeriodicSync().
 */
public class SftpSyncService {
    private final Config cfg;
    private final SyncState state;
    private Session session;
    private ChannelSftp channel;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public SftpSyncService(Config cfg, SyncState state) {
        this.cfg = cfg;
        this.state = state;
        try { Files.createDirectories(cfg.getLocalDir()); } catch (Exception ignored) {}
    }

    private void connect() throws Exception {
        if (session != null && session.isConnected() && channel != null && channel.isConnected()) return;
        JSch jsch = new JSch();
        session = jsch.getSession(cfg.getFtpUser(), cfg.getFtpHost(), cfg.getFtpPort());
        session.setConfig("StrictHostKeyChecking", "no");
        session.setPassword(cfg.getFtpPass());
        session.connect(10000);
        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(10000);
    }

    private void disconnect() {
        try { if (channel != null) channel.disconnect(); } catch (Exception ignored) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
    }

    /**
     * Inicia sincronización periódica remoto->local con el intervalo definido en cfg.getPollSeconds().
     */
    public void startPeriodicSync() {
        long interval = Math.max(1, cfg.getPollSeconds());
        scheduler.scheduleAtFixedRate(() -> {
            try {
                syncRemoteToLocal();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, interval, TimeUnit.SECONDS);
    }

    /**
     * Para scheduler y cierra conexión.
     */
    public void stop() {
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}
        disconnect();
    }

    /**
     * Sincroniza recursivamente el directorio remoto hacia local:
     * - detecta renames moviendo ficheros locales si coinciden por size+mtime
     * - descarga ficheros nuevos
     * - elimina locales que ya no existen en remoto cuando procedan del remoto o se subieron antes
     */
    public void syncRemoteToLocal() throws Exception {
        connect();
        String remoteBase = cfg.getRemoteDir();
        Path localBase = cfg.getLocalDir();

        Map<String, RemoteMeta> remoteFiles = new HashMap<>();
        collectRemoteFiles(remoteBase, "", remoteFiles);

        Map<String, LocalMeta> localFiles = new HashMap<>();
        try {
            Files.walk(localBase).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        Path rel = localBase.relativize(p);
                        String relUnix = rel.toString().replace(File.separatorChar, '/');
                        localFiles.put(relUnix, new LocalMeta(p, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}

        // procesar remotos: mover si candidato, o descargar
        for (Map.Entry<String, RemoteMeta> e : remoteFiles.entrySet()) {
            String rel = e.getKey();
            RemoteMeta r = e.getValue();
            Path targetLocal = localBase.resolve(rel.replace("/", File.separator));
            LocalMeta local = localFiles.get(rel);

            if (local != null) {
                if (local.size == r.size) continue;
            }

            Optional<LocalMeta> candidate = localFiles.values().stream()
                    .filter(lm -> !lm.path.equals(targetLocal) && lm.size == r.size &&
                            Math.abs(lm.lastModified - (r.mtime * 1000L)) < 2000L)
                    .findFirst();

            if (candidate.isPresent()) {
                try {
                    Files.createDirectories(targetLocal.getParent());
                    Files.move(candidate.get().path, targetLocal, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Moved local (rename remoto detectado): " + candidate.get().path + " -> " + targetLocal);
                    String oldRel = localBase.relativize(candidate.get().path).toString().replace(File.separatorChar, '/');
                    localFiles.remove(oldRel);
                    localFiles.put(rel, new LocalMeta(targetLocal, candidate.get().size, candidate.get().lastModified));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                continue;
            }

            // descargar
            try {
                Files.createDirectories(targetLocal.getParent());
                try (InputStream in = channel.get(r.remotePath)) {
                    Files.copy(in, targetLocal, StandardCopyOption.REPLACE_EXISTING);
                }
                state.markDownloaded(targetLocal);
                System.out.println("Downloaded: " + r.remotePath + " -> " + targetLocal);
            } catch (SftpException | IOException ex) {
                ex.printStackTrace();
            }
        }

        // procesar locales que ya no están en remoto
        for (Map.Entry<String, LocalMeta> le : localFiles.entrySet()) {
            String rel = le.getKey();
            if (remoteFiles.containsKey(rel)) continue;

            LocalMeta lm = le.getValue();
            Path fullLocal = lm.path;
            boolean wasDownloaded = state.getDownloadedTimestamp(fullLocal) != null;
            Long uploadedTs = state.getLastUploaded(fullLocal);

            if (wasDownloaded || uploadedTs != null) {
                try {
                    Files.deleteIfExists(fullLocal);
                    state.removeUploaded(fullLocal);
                    state.removeDownloaded(fullLocal);
                    System.out.println("Deleted local (propagated remote deletion): " + fullLocal);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    // recoge archivos remotos recursivamente (clave = ruta relativa unix)
    private void collectRemoteFiles(String remoteBase, String relPrefix, Map<String, RemoteMeta> out) throws SftpException {
        String pathToList = remoteBase;
        if (relPrefix != null && !relPrefix.isEmpty()) {
            pathToList = remoteBase.endsWith("/") ? remoteBase + relPrefix : remoteBase + "/" + relPrefix;
        }
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> entries = channel.ls(pathToList);
        for (ChannelSftp.LsEntry ent : entries) {
            String name = ent.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String childRel = (relPrefix == null || relPrefix.isEmpty()) ? name : relPrefix + "/" + name;
            String childRemote = pathToList.endsWith("/") ? pathToList + name : pathToList + "/" + name;

            if (ent.getAttrs().isDir()) {
                collectRemoteFiles(remoteBase, childRel, out);
            } else if (ent.getAttrs().isReg()) {
                long size = ent.getAttrs().getSize();
                int mtime = ent.getAttrs().getMTime();
                out.put(childRel, new RemoteMeta(childRemote, size, mtime));
            }
        }
    }

    /**
     * Sube un fichero local manteniendo la estructura relativa respecto a cfg.getLocalDir().
     */
    public void uploadFile(Path localPath) {
        if (localPath == null) return;
        try {
            connect();

            Path base = cfg.getLocalDir();
            String relUnix;
            try {
                Path rel = base.relativize(localPath);
                relUnix = rel.toString().replace(File.separatorChar, '/');
            } catch (IllegalArgumentException iae) {
                relUnix = localPath.getFileName().toString();
            }

            String remotePath = cfg.getRemoteDir() == null || cfg.getRemoteDir().isEmpty()
                    ? relUnix
                    : (cfg.getRemoteDir().endsWith("/") ? cfg.getRemoteDir() + relUnix : cfg.getRemoteDir() + "/" + relUnix);

            String parentRemote = remotePath.contains("/") ? remotePath.substring(0, remotePath.lastIndexOf('/')) : "";
            if (!parentRemote.isEmpty()) ensureRemoteDirExists(parentRemote);

            try (InputStream in = Files.newInputStream(localPath)) {
                channel.put(in, remotePath, ChannelSftp.OVERWRITE);
                long lastMod = Files.getLastModifiedTime(localPath).toMillis();
                state.markUploaded(localPath, lastMod);
                System.out.println("Uploaded " + remotePath);
            }
        } catch (SftpException se) {
            System.err.println("SFTP error uploading " + localPath + ": " + se.getMessage());
            se.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Elimina en remoto la ruta correspondiente al fichero/directorio local pasado.
     */
    public void deleteRemote(Path localPath) {
        if (localPath == null) return;
        try {
            connect();

            Path base = cfg.getLocalDir();
            String relUnix;
            try {
                Path rel = base.relativize(localPath);
                relUnix = rel.toString().replace(File.separatorChar, '/');
            } catch (IllegalArgumentException iae) {
                relUnix = localPath.getFileName().toString();
            }

            String remotePath = cfg.getRemoteDir() == null || cfg.getRemoteDir().isEmpty()
                    ? relUnix
                    : (cfg.getRemoteDir().endsWith("/") ? cfg.getRemoteDir() + relUnix : cfg.getRemoteDir() + "/" + relUnix);

            try {
                channel.rm(remotePath);
                System.out.println("Remote deleted: " + remotePath);
            } catch (SftpException se) {
                try {
                    channel.rmdir(remotePath);
                    System.out.println("Remote dir deleted: " + remotePath);
                } catch (SftpException se2) {
                    System.out.println("No se pudo eliminar remoto (posible inexistente): " + remotePath + " -> " + se.getMessage());
                }
            }

            state.removeUploaded(localPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // asegura la existencia de un directorio remoto
    private void ensureRemoteDirExists(String remoteDir) throws SftpException {
        if (remoteDir == null || remoteDir.isEmpty()) return;
        String path = remoteDir.replaceAll("/+", "/");
        if (!path.startsWith("/")) path = "/" + path;

        channel.cd("/");
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            try {
                channel.cd(part);
            } catch (SftpException e) {
                channel.mkdir(part);
                channel.cd(part);
            }
        }
    }

    // metadata helpers
    private static class RemoteMeta {
        final String remotePath;
        final long size;
        final int mtime;

        RemoteMeta(String remotePath, long size, int mtime) {
            this.remotePath = remotePath;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private static class LocalMeta {
        final Path path;
        final long size;
        final long lastModified;

        LocalMeta(Path path, long size, long lastModified) {
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
