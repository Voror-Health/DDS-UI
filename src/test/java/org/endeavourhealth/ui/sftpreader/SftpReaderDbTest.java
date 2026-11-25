package org.endeavourhealth.ui.sftpreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.endeavourhealth.ui.endpoints.SftpReaderEndpoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SftpReaderDbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    /** The row as vhp-sftpreader has it, with the nested pool block. */
    private static final String ROW = "{\n"
            + "  \"url\" : \"jdbc:mysql://localhost:3306/sftpreader?cachePrepStmts=true&prepStmtCacheSize=250\",\n"
            + "  \"username\" : \"sftpreader\",\n"
            + "  \"password\" : \"pw-SECRET\",\n"
            + "  \"pool\" : { \"min-idle\" : 2, \"max-pool-size\" : 10 }\n"
            + "}";

    @Test
    void readsTheSameRowAsTheReader() {
        assertEquals("database", SftpReaderDb.CONFIG_ID);
        assertEquals("sftpreader", SftpReaderDb.APP_ID);
    }

    @Test
    void readsTheSharedVhpDatabaseRowFormat() throws Exception {
        SftpReaderDb.Settings s = SftpReaderDb.Settings.fromJson(json(ROW));

        assertEquals("jdbc:mysql://localhost:3306/sftpreader?cachePrepStmts=true&prepStmtCacheSize=250", s.url());
        assertEquals("sftpreader", s.username());
        assertEquals("pw-SECRET", s.password());
        assertEquals(2, s.minIdle());
        assertEquals(10, s.maxPoolSize());
    }

    @Test
    void poolBlockIsOptional() throws Exception {
        SftpReaderDb.Settings s = SftpReaderDb.Settings.fromJson(
                json("{\"url\":\"jdbc:mysql://h/db\",\"username\":\"u\",\"password\":\"p\"}"));
        assertEquals(1, s.minIdle());
        assertEquals(3, s.maxPoolSize());
    }

    @Test
    void missingRowSaysWhatToAdd() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> SftpReaderDb.Settings.fromJson(null));
        assertTrue(ex.getMessage().contains("'database'") && ex.getMessage().contains("sftpreader")
                && ex.getMessage().contains("db_sftp_reader"), ex.getMessage());
    }

    @Test
    void onlyMySqlIsAccepted() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> SftpReaderDb.Settings.fromJson(
                json("{\"url\":\"jdbc:postgresql://h/db\",\"username\":\"u\",\"password\":\"p\"}")));
        assertTrue(ex.getMessage().contains("MySQL"), ex.getMessage());
    }

    @Test
    void missingFieldsAndBadPoolAreErrors() {
        assertThrows(IllegalStateException.class, () -> SftpReaderDb.Settings.fromJson(
                json("{\"url\":\"jdbc:mysql://h/db\",\"username\":\"u\"}")));
        assertThrows(IllegalStateException.class, () -> SftpReaderDb.Settings.fromJson(
                json("{\"username\":\"u\",\"password\":\"p\"}")));
        assertThrows(IllegalStateException.class, () -> SftpReaderDb.Settings.fromJson(
                json("{\"url\":\"jdbc:mysql://h/db\",\"username\":\"u\",\"password\":\"p\",\"pool\":{\"min-idle\":5,\"max-pool-size\":2}}")));
    }

    @Test
    void toStringNeverContainsThePassword() throws Exception {
        String s = SftpReaderDb.Settings.fromJson(json(ROW)).toString();
        assertFalse(s.contains("pw-SECRET"), s);
        assertFalse(s.contains("cachePrepStmts"), s);
        assertTrue(s.contains("jdbc:mysql://localhost:3306/sftpreader"), s);
    }

    @Test
    void poolIsConfiguredLikeTheLibraryPoolWas() throws Exception {
        // HikariDataSource connects lazily, so no database is needed to check its settings
        try (HikariDataSource ds = SftpReaderDb.createPool(SftpReaderDb.Settings.fromJson(json(ROW)))) {
            assertFalse(ds.isAutoCommit());
            assertEquals(2, ds.getMinimumIdle());
            assertEquals(10, ds.getMaximumPoolSize());
            assertEquals("sftpreader", ds.getUsername());
            assertTrue(ds.getJdbcUrl().startsWith("jdbc:mysql://localhost:3306/sftpreader"));
        }
    }

    @Test
    void endpointNoLongerUsesTheLibraryConnectionManager() throws Exception {
        // the old path read the 'db_sftp_reader' row, which deployments don't have (the page answered 500)
        Path src = Path.of("src/main/java/" + SftpReaderEndpoint.class.getName().replace('.', '/') + ".java");
        String code = Files.readString(src);
        assertFalse(code.contains("ConnectionManager"), "SftpReaderEndpoint must use SftpReaderDb");
        assertFalse(code.contains(" log."), "the PostgreSQL schema-qualified queries were removed");
        assertTrue(code.contains("SftpReaderDb.getConnection()"));
    }
}
