// java
package org.juanrdzbaeza.daemon;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servicio SFTP mejorado: mantiene SyncState y crea directorio remoto si hace falta.
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

    public void startPeriodicSync() {
        scheduler.scheduleAtFixedRate(() -> {
            try { syncRemoteToLocal(); } catch (Exception e) { e.printStackTrace(); }
        }, 0, cfg.getPollSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        disconnect();
    }

    public void syncRemoteToLocal() throws Exception {
        connect();
        try {
            ensureRemoteDirExists(cfg.getRemoteDir());
            channel.cd(cfg.getRemoteDir());
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> files = channel.ls(".");
            for (ChannelSftp.LsEntry e : files) {
                if (e.getAttrs().isReg()) {
                    Path local = cfg.getLocalDir().resolve(e.getFilename());
                    long remoteSize = e.getAttrs().getSize();
                    if (Files.exists(local) && Files.size(local) == remoteSize) continue;
                    try (InputStream in = channel.get(e.getFilename());
                         OutputStream out = Files.newOutputStream(local)) {
                        in.transferTo(out);
                        // marcar como descargado para que LocalWatcher ignore el evento inmediato
                        state.markDownloaded(local);
                        System.out.println("Downloaded: " + e.getFilename());
                    } catch (SftpException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        } finally {
            // no desconectamos aquí para reusar la sesión
        }
    }

    public void uploadFile(Path localPath) {
        try {
            connect();
            ensureRemoteDirExists(cfg.getRemoteDir());
            channel.cd(cfg.getRemoteDir());
            String remoteName = localPath.getFileName().toString();
            try (InputStream in = Files.newInputStream(localPath)) {
                // usar OVERWRITE para evitar errores por archivo existente
                channel.put(in, remoteName, ChannelSftp.OVERWRITE);
                System.out.println("Uploaded " + remoteName);
                // opcional: marcar upload en el estado con el lastModified local
                long lastMod = Files.getLastModifiedTime(localPath).toMillis();
                state.markUploaded(localPath, lastMod);
            }
        } catch (SftpException se) {
            System.err.println("SFTP error uploading " + localPath + ": " + se.getMessage());
            se.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ensureRemoteDirExists(String remoteDir) throws SftpException {
        if (remoteDir == null || remoteDir.isEmpty() || remoteDir.equals(".")) return;
        // navegamos desde la raíz para crear recursivamente
        channel.cd("/");
        String[] parts = remoteDir.split("/");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            try {
                channel.cd(part);
            } catch (SftpException e) {
                // crear y entrar
                channel.mkdir(part);
                channel.cd(part);
            }
        }
    }
}
