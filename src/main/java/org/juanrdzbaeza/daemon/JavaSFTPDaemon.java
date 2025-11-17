// java
package org.juanrdzbaeza.daemon;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JavaSFTPDaemon {
    public static void main(String[] args) throws IOException {
        String cfgPath = (args != null && args.length > 0) ? args[0] : "config.properties";
        Config cfg = Config.load(cfgPath);

        // Estado compartido entre watcher y servicio SFTP
        SyncState state = new SyncState();

        // Instanciar servicio SFTP con el estado
        SftpSyncService sftp = new SftpSyncService(cfg, state);

        // Watcher que recibe también el estado para evitar bucles
        LocalWatcher watcher = new LocalWatcher(cfg.getLocalDir(), sftp, state);

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
