package org.endeavourhealth.ui.sftpreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

/**
 * VHP port: connections to the SFTP Reader's own database, for the SFTP Reader page ({@code SftpReaderEndpoint}).
 *
 * <p>The page used to get them from the Endeavour {@code ConnectionManager}, which reads a config row called
 * {@code db_sftp_reader}. The ported reader (vhp-sftpreader) no longer uses that row, and deployments don't have it,
 * so the page failed with a 500. This reads the same row the reader does: {@code database} under app_id
 * {@code sftpreader} (falling back to {@code global}):</p>
 * <pre>
 * {
 *   "url": "jdbc:mysql://&lt;host&gt;:3306/sftpreader",            (required, MySQL only)
 *   "username": "...", "password": "...",                     (required)
 *   "pool": { "min-idle": 1, "max-pool-size": 3 }             (optional)
 * }
 * </pre>
 *
 * <p>A copy of vhp-sftpreader's {@code model/SftpReaderDb}, except that the app_id is given explicitly (this
 * webapp's own app_id is not {@code sftpreader}) and there is no non-pooled connection. Connections come from a
 * HikariCP pool with auto-commit off, as {@code ConnectionManager.getSftpReaderConnection()} gave them.</p>
 */
public final class SftpReaderDb {
    private static final Logger LOG = LoggerFactory.getLogger(SftpReaderDb.class);

    public static final String CONFIG_ID = "database";
    public static final String APP_ID = "sftpreader";

    /** What the {@code database} config row says. */
    public record Settings(String url, String username, String password, int minIdle, int maxPoolSize) {

        static final int DEFAULT_MIN_IDLE = 1;
        static final int DEFAULT_MAX_POOL_SIZE = 3;

        static Settings fromJson(JsonNode node) {
            if (node == null) {
                throw new IllegalStateException("No '" + CONFIG_ID + "' config found for app_id " + APP_ID
                        + " (or global) - the SFTP Reader page reads the SFTP Reader database from the same row as"
                        + " vhp-sftpreader: {\"url\": \"jdbc:mysql://...\", \"username\": \"...\", \"password\": \"...\"}"
                        + " (the old 'db_sftp_reader' row is no longer used)");
            }
            String url = required(node, "url");
            if (!url.startsWith("jdbc:mysql:")) {
                throw new IllegalStateException("The SFTP Reader database in the '" + CONFIG_ID
                        + "' config (app_id " + APP_ID + ") must be MySQL (a jdbc:mysql: url)");
            }
            String username = required(node, "username");
            String password = required(node, "password");

            JsonNode pool = node.path("pool");
            int minIdle = pool.path("min-idle").asInt(DEFAULT_MIN_IDLE);
            int maxPoolSize = pool.path("max-pool-size").asInt(DEFAULT_MAX_POOL_SIZE);
            if (maxPoolSize < 1 || minIdle < 0 || minIdle > maxPoolSize) {
                throw new IllegalStateException("'pool' in the '" + CONFIG_ID + "' config (app_id " + APP_ID
                        + ") is not valid: min-idle=" + minIdle + ", max-pool-size=" + maxPoolSize);
            }
            return new Settings(url, username, password, minIdle, maxPoolSize);
        }

        private static String required(JsonNode node, String field) {
            String value = node.path(field).asText("").trim();
            if (value.isEmpty()) {
                throw new IllegalStateException("'" + field + "' must be set in the '" + CONFIG_ID
                        + "' config (app_id " + APP_ID + ")");
            }
            return value;
        }

        @Override
        public String toString() {
            //never log the password; the url can carry one too, so drop its query string
            int q = url.indexOf('?');
            return "Settings[url=" + (q == -1 ? url : url.substring(0, q)) + ", username=" + username
                    + ", minIdle=" + minIdle + ", maxPoolSize=" + maxPoolSize + "]";
        }
    }

    private static volatile HikariDataSource pool;

    private SftpReaderDb() {
    }

    /** A pooled connection, auto-commit off (as {@code ConnectionManager.getSftpReaderConnection()} gave). */
    public static Connection getConnection() throws Exception {
        HikariDataSource p = pool;
        if (p == null || p.isClosed()) {
            synchronized (SftpReaderDb.class) {
                p = pool;
                if (p == null || p.isClosed()) {
                    Settings s = Settings.fromJson(ConfigManager.getConfigurationAsJson(CONFIG_ID, APP_ID));
                    p = createPool(s);
                    pool = p;
                    LOG.info("SFTP Reader database pool created: {}", s);
                }
            }
        }
        return p.getConnection();
    }

    /** Same pool defaults as the Endeavour ConnectionManager (and vhp-sftpreader) use for this database. */
    static HikariDataSource createPool(Settings s) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("dds-ui-sftpreader");
        ds.setJdbcUrl(s.url());
        ds.setUsername(s.username());
        ds.setPassword(s.password());
        ds.setMinimumIdle(s.minIdle());
        ds.setMaximumPoolSize(s.maxPoolSize());
        ds.setIdleTimeout(60000);
        ds.setAutoCommit(false);
        ds.setConnectionTimeout(300000L);
        ds.setLeakDetectionThreshold(5000L);
        return ds;
    }

    public static synchronized void shutdown() {
        HikariDataSource p = pool;
        pool = null;
        if (p != null) {
            p.close();
        }
    }
}
