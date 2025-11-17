// java
package org.juanrdzbaeza.daemon;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuración del daemon de sincronización FTP \<-> local.
 *
 * <p>Lee las propiedades desde un fichero de propiedades (por ejemplo `config.properties`)
 * y expone accesores para los valores más relevantes.</p>
 *
 * <p>Propiedades soportadas (con sus valores por defecto):</p>
 * <ul>
 *   <li>{@code ftp.host} — {@code localhost}</li>
 *   <li>{@code ftp.port} — {@code 21}</li>
 *   <li>{@code ftp.user} — {@code anonymous}</li>
 *   <li>{@code ftp.pass} — {@code } (vacío)</li>
 *   <li>{@code local.dir} — {@code sync} (ruta relativa al working directory)</li>
 *   <li>{@code remote.dir} — {@code /}</li>
 *   <li>{@code poll.seconds} — {@code 30}</li>
 * </ul>
 *
 * <p>Ejemplo de `config.properties`:</p>
 * <pre>{@code
 * ftp.host=ftp.example.com
 * ftp.port=21
 * ftp.user=miusuario
 * ftp.pass=miclave
 * local.dir=C:/Users/juan/sync
 * remote.dir=/uploads
 * poll.seconds=30
 * }</pre>
 *
 * <p>Notas:</p>
 * <ul>
 *   <li>El fichero se carga con {@link #load(String)} usando la ruta proporcionada. Si usas
 *   una ruta relativa, se resuelve contra el directorio de trabajo del proceso (en IntelliJ
 *   normalmente la raíz del proyecto).</li>
 *   <li>En Windows se recomiendan barras `/` o dobles barras invertidas (`\\`) en rutas.
 *   Por ejemplo {@code C:/Users/juan/sync}.</li>
 *   <li>La clase sólo encapsula valores; validaciones adicionales (por ejemplo permisos,
 *   existencia remota, validación de puertos) deben hacerse fuera de aquí.</li>
 *   <li>`local.dir` es creado por el servicio si no existe, pero {@link org.juanrdzbaeza.daemon.LocalWatcher}
 *   registra solo el directorio raíz y no vigila recursivamente subdirectorios por defecto.</li>
 * </ul>
 *
 * @since 0.1
 */
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
