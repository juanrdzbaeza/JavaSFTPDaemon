// java
package org.juanrdzbaeza.daemon;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class Config {
    private final String ftpHost;
    private final int ftpPort;
    private final String ftpUser;
    private final String ftpPass;
    private final Path localDir;
    private final String remoteDir;
    private final int pollSeconds;

    private Config(Properties p) {
        this.ftpHost = p.getProperty("ftp.host", "localhost");
        this.ftpPort = Integer.parseInt(p.getProperty("ftp.port", "21"));
        this.ftpUser = p.getProperty("ftp.user", "anonymous");
        this.ftpPass = p.getProperty("ftp.pass", "");
        this.localDir = Path.of(p.getProperty("local.dir", "sync"));
        this.remoteDir = p.getProperty("remote.dir", "/");
        this.pollSeconds = Integer.parseInt(p.getProperty("poll.seconds", "30"));
    }

    public static Config load(String path) throws IOException {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            p.load(in);
        }
        return new Config(p);
    }

    public String getFtpHost() { return ftpHost; }
    public int getFtpPort() { return ftpPort; }
    public String getFtpUser() { return ftpUser; }
    public String getFtpPass() { return ftpPass; }
    public Path getLocalDir() { return localDir; }
    public String getRemoteDir() { return remoteDir; }
    public int getPollSeconds() { return pollSeconds; }
}
