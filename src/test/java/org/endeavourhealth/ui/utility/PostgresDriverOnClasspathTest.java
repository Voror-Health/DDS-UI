package org.endeavourhealth.ui.utility;

import org.endeavourhealth.core.database.rdbms.ConnectionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ConnectionManager.isPostgreSQL() references org.postgresql.jdbc.PgConnection directly, and the SFTP Reader and
 * HL7 Receiver endpoints call it. Without the PostgreSQL driver on the classpath they fail with NoClassDefFoundError.
 */
class PostgresDriverOnClasspathTest {

    @Test
    void isPostgreSqlIsFalseForANonPostgresConnection() {
        Connection mysqlLike = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("unwrap")) {
                        throw new SQLException("not a wrapper for " + args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        assertFalse(ConnectionManager.isPostgreSQL(mysqlLike));
    }
}
