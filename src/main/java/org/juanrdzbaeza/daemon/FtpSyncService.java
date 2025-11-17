package org.juanrdzbaeza.daemon;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FtpSyncService {
    private final Config cfg;
    private final FTPClient ftp = new FTPClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public FtpSyncService(Config cfg) {
        this.cfg = cfg;
        try {
            Files.createDirectories(cfg.getLocalDir());
        } catch (IOException ignored) {}
    }

    private void connect() throws IOException {
        if (ftp.isConnected()) return;
        ftp.connect(cfg.getFtpHost(), cfg.getFtpPort());
        if (!ftp.login(cfg.getFtpUser(), cfg.getFtpPass())) {
            throw new IOException("FTP login failed");
        }
        ftp.enterLocalPassiveMode();
    }

    private void disconnect() {
        try { if (ftp.isConnected()) ftp.logout(); } catch (IOException ignored) {}
        try { if (ftp.isConnected()) ftp.disconnect(); } catch (IOException ignored) {}
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

    public void syncRemoteToLocal() throws IOException {
        connect();
        ftp.changeWorkingDirectory(cfg.getRemoteDir());
        FTPFile[] files = ftp.listFiles();
        for (FTPFile f : files) {
            if (f.isFile()) {
                Path local = cfg.getLocalDir().resolve(f.getName());
                if (Files.exists(local) && Files.size(local) == f.getSize()) continue; // mínimo
                try (FileOutputStream out = new FileOutputStream(local.toFile())) {
                    InputStream in = ftp.retrieveFileStream(f.getName());
                    if (in == null) continue;
                    in.transferTo(out);
                    in.close();
                    ftp.completePendingCommand();
                    System.out.println("Downloaded: " + f.getName());
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public void uploadFile(Path localPath) {
        try {
            connect();
            ftp.changeWorkingDirectory(cfg.getRemoteDir());
            try (InputStream in = Files.newInputStream(localPath)) {
                boolean ok = ftp.storeFile(localPath.getFileName().toString(), in);
                System.out.println("Uploaded " + localPath.getFileName() + " -> " + ok);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

