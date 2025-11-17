# JavaSFTPDaemon

JavaSFTPDaemon es un pequeño daemon en Java para sincronizar de forma automática un directorio local con un servidor remoto (SFTP / FTP), imitando el comportamiento básico de clientes tipo Dropbox/Google Drive: subida automática de cambios locales, descarga periódica de cambios remotos, y propagación de eliminaciones/renombrados.

Estado
-------
Proyecto en estado de prototipo. Funcionalidades implementadas:

- Sincronización periódica remoto -> local (recursiva).
- Detección de cambios locales (creación/modificación) y subida automática a remoto.
- Propagación de eliminaciones locales hacia remoto.
- Detección básica de renombrados remotos (mueve archivos locales si coinciden por tamaño/fecha).
- Evita bucles simples (descarga que genera un evento local inmediato) mediante estado compartido.

Requisitos
----------
- Java 17+ (JDK instalado)
- Maven (para construir el JAR)
- Conexión al servidor SFTP o FTP (el código usa JSch para SFTP; si necesitas FTP puro hay una clase de ejemplo con Apache Commons Net).

Dependencias principales (ya declaradas en `pom.xml`):
- com.jcraft:jsch (SFTP)
- commons-net (FTP, opcional)
- slf4j-simple (logging)

Configuración
-------------
Copia el archivo de ejemplo `config.properties.example` a `config.properties` (no subas `config.properties` a GitHub). Rellena los valores sensibles (host, usuario, contraseña).

Ejemplo mínimo de `config.properties` (usar rutas Windows con `/` o `\\`):

```properties
# Host del servidor (SFTP/FTP)
ftp.host=ftp.example.com
# Puerto: 22 para SFTP, 21 para FTP
ftp.port=22
# Usuario y contraseña
ftp.user=miusuario
ftp.pass=miclave
# Directorio local a sincronizar (por defecto: sync). Ejemplo Windows:
local.dir=C:/Users/juan/sync
# Directorio remoto base (por defecto: /)
remote.dir=/uploads
# Intervalo en segundos para poll remoto
poll.seconds=30
```

Importante de seguridad
-----------------------
- Nunca subas `config.properties` con credenciales a repositorios públicos. Usa `config.properties.example` con valores ofuscados.
- Por simplicidad `StrictHostKeyChecking` está desactivado en la conexión SFTP; en entornos de producción deberías habilitar comprobación de huella y/o usar autenticación por llave privada.

Construir y ejecutar (Windows - cmd)
-----------------------------------
1) Construir con Maven:

```bat
mvn clean package
```

2) Ejecutar el JAR generado pasando la ruta del `config.properties` si no está en el working directory:

```bat
rem Ejecutar usando el JAR
java -jar target\JavaSFTPDaemon-1.0-SNAPSHOT.jar config.properties

rem O ejecutar desde clases (útil en desarrollo):
java -cp target\classes;target\dependency\* org.juanrdzbaeza.daemon.JavaSFTPDaemon config.properties
```

Nota: si ejecutas desde IntelliJ, en la configuración de la Run Configuration puedes pasar la ruta a `config.properties` como argumento y comprobar el "Working directory".

Comportamiento de sincronización
--------------------------------
- Descargas remotas: el daemon sincroniza recursivamente el contenido de `remote.dir` al `local.dir`, creando subdirectorios según sea necesario.
- Subidas locales: cambios en archivos locales (create/modify) se subirán al remoto manteniendo la estructura relativa. Para evitar subir archivos aún en escritura, el watcher espera a que el tamaño se estabilice.
- Eliminaciones locales: si borras un fichero local, el daemon intentará borrar el fichero correspondiente en remoto.
- Eliminaciones remotas: si se elimina en remoto, al siguiente poll se eliminará el fichero local si dicho fichero provenía del remoto (o se había subido anteriormente).
- Renombrados locales: son detectados en forma de DELETE + CREATE; el efecto en remoto será la eliminación del antiguo nombre y la subida del nuevo.
- Renombrados remotos: el daemon intenta detectar renombrados remotos moviendo el fichero local cuando encuentra un archivo remoto nuevo con tamaño/fecha que coincide con otro local existente.

Limitaciones y consideraciones
------------------------------
- WatchService de Java no es recursivo por defecto; el proyecto registra subdirectorios y registra dinámicamente nuevos directorios, pero hay límites en rendimiento para árboles muy grandes.
- La detección de renombrados remotos es heurística (basada en tamaño y mtime) y puede fallar en casos límites.
- No hay conflicto avanzado (ediciones simultáneas) ni resolución basada en hashes; actualmente se usa tamaño/mtime y última operación gana.
- No hay colas persistentes: en un reinicio se relanzan sincronizaciones desde cero.
- Manejar archivos grandes o conexiones poco fiables puede requerir reintentos/resume y verificación por hash.

Desarrollo y contribuciones
---------------------------
- Añade issues para bugs o mejoras (colas, control de versiones, GUI, deduplicación, ACLs, etc.).
- Para pruebas locales puedes usar un servidor SFTP/FTP de pruebas (p. ej. OpenSSH server, vsftpd, o contenedores Docker con servidores de prueba).

Archivos relevantes
-------------------
- `src/main/java/org/juanrdzbaeza/daemon/Config.java` — carga `config.properties`.
- `src/main/java/org/juanrdzbaeza/daemon/SftpSyncService.java` — sincronización y operaciones SFTP.
- `src/main/java/org/juanrdzbaeza/daemon/LocalWatcher.java` — WatchService local y subida de cambios.
- `src/main/java/org/juanrdzbaeza/daemon/SyncState.java` — estado compartido para evitar bucles y trackear operaciones.

Licencia
--------
Proyecto bajo la licencia incluida en `LICENSE`.

Contacto
--------
Si quieres que te ayude a mejorar la robustez (reintentos, colas, resolución de conflictos, o autenticación por llave), dime y lo implementamos.
