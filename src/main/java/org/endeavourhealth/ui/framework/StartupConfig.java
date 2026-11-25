package org.endeavourhealth.ui.framework;

import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.endeavourhealth.common.config.ConfigManager;
import org.endeavourhealth.common.config.ConfigManagerException;
import org.endeavourhealth.common.utility.MetricsHelper;
import org.endeavourhealth.core.database.dal.usermanager.caching.CacheManager;
import org.endeavourhealth.core.database.dal.usermanager.caching.ProjectCache;
import org.endeavourhealth.core.queueing.QueueHelper;
import org.endeavourhealth.core.database.rdbms.ConnectionManager;
import org.endeavourhealth.ui.datasteward.DataStewardClient;
import org.endeavourhealth.ui.datasteward.DataStewardConfig;
import org.endeavourhealth.ui.sftpreader.SftpReaderDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.security.CodeSource;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * Jakarta port of {@code org.endeavourhealth.coreui.framework.StartupConfig} (common/api), plus the
 * DataSteward client used by the publisher editor's DSM details.
 */
public final class StartupConfig implements ServletContextListener {
    private static final Logger LOG = LoggerFactory.getLogger(StartupConfig.class);

    @Override
    public void contextInitialized(ServletContextEvent contextEvent) {
        String configId = contextEvent.getServletContext().getInitParameter("app_id");
        if (configId == null || configId.isEmpty()) {
            LOG.warn("Application id (app_id) not set in context file (web.xml), reverting to 'default'");
            configId = "default";
        }

        LOG.trace("Initializing configuration manager for app ID [" + configId + "]");
        try {
            ConfigManager.Initialize(configId);
        } catch (ConfigManagerException e) {
            LOG.error(e.getMessage());
        }

        //now we've set our app name, we can start our heartbeat reporting to Graphite
        MetricsHelper.startHeartbeat();

        initialiseDataSteward();
        checkOverride(ProjectCache.class, "subscriber lookups will read the old DSM tables");
        checkOverride(QueueHelper.class, "Post to exchange will not find the VhpInbound/VhpProtocol exchange config");
    }

    /**
     * The library overrides in this webapp (WEB-INF/classes) must shadow the copies in the Endeavour jars:
     * ProjectCache (subscriber lookup via DataSteward) and QueueHelper (Vhp* exchange names).
     * Make it obvious in the log if the container ever loads a jar's copy.
     */
    private static void checkOverride(Class<?> cls, String consequence) {
        CodeSource source = cls.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        String where = location == null ? "unknown" : location.toString();
        if (where.endsWith(".jar") || where.contains(".jar!")) {
            LOG.error("{} was loaded from {} instead of WEB-INF/classes - {}", cls.getSimpleName(), where, consequence);
        } else {
            LOG.info("{} override active (loaded from {})", cls.getSimpleName(), where);
        }
    }

    /**
     * DataSteward answers the publisher editor's "DSM details" and the subscriber lookups (ProjectCache override).
     * A missing config does not stop the app, but those calls fail until it is fixed.
     */
    private static void initialiseDataSteward() {
        try {
            DataStewardConfig config = DataStewardConfig.fromConfigManager();
            DataStewardClient.initialise(DataStewardClient.create(config));
            LOG.info("DataSteward client enabled: {}", config);
        } catch (Exception ex) {
            LOG.error("Failed to initialise DataSteward client - DSM details and subscriber lookups will fail until the 'datasteward' config is fixed", ex);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent contextEvent) {
        DataStewardClient.shutdown();

        ConnectionManager.shutdown();
        SftpReaderDb.shutdown(); //VHP: the SFTP Reader page's own pool; before the drivers are deregistered
        cleanupJDBCDrivers();
        CacheManager.stopScheduler();

        System.out.println("Cleaning up ConfigManager/Logback...");
        ConfigManager.shutdownLogback();
        System.out.println("...Shutdown done.");
    }

    private void cleanupJDBCDrivers() {
        AbandonedConnectionCleanupThread.checkedShutdown();

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == cl) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException ex) {
                    LOG.error("Error deregistering JDBC driver {}", driver, ex);
                }
            }
        }
    }
}
