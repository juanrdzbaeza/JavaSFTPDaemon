// java
package org.juanrdzbaeza.daemon;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JavaSFTPDaemon {
    public static void main(String[] args) throws IOException {
        Config cfg = Config.load("config.properties");
        FtpSyncService ftp = new FtpSyncService(cfg);
        SftpSyncService sftp = new SftpSyncService(cfg);
        LocalWatcher watcher = new LocalWatcher(cfg.getLocalDir(), sftp);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(() -> {
            try {
                sftp.startPeriodicSync();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        exec.submit(() -> {
            try {
                watcher.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                watcher.stop();
                sftp.stop();
                exec.shutdownNow();
            } catch (Exception ignored) {}
        }));

        System.out.println("Daemon started. Press Ctrl+C to stop.");
    }
}
