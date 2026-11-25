package org.endeavourhealth.ui.endpoints;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.endeavourhealth.common.config.ConfigManager;
import org.endeavourhealth.core.xml.QueryDocument.LibraryItem;
import org.endeavourhealth.core.xml.QueryDocument.TechnicalInterface;
import org.endeavourhealth.ui.json.JsonFolderContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in: the System Config page's calls against a real MySQL whose admin database starts empty (as on a new
 * deployment). Needs a throwaway server with a {@code config} database ({@code config(app_id, config_id, config_data)}
 * holding {@code db_admin} and {@code db_audit} rows) and the {@code admin} and {@code audit} tables from
 * {@code endeavour/EDS/src/database/mySQL}. Run with
 * {@code -Ddds.test.mysql=true -Ddds.test.config.url=jdbc:mysql://...:.../config -Ddds.test.config.username=...}.
 * It inserts System items and never removes them, so point it at a throwaway server.
 */
@EnabledIfSystemProperty(named = "dds.test.mysql", matches = "true")
class SystemLibraryMySqlIntegrationTest {

    private static final LibraryEndpoint ENDPOINT = new LibraryEndpoint();
    private static final SecurityContext ADMIN = new SecurityContext() {
        public Principal getUserPrincipal() { return () -> "test-admin"; }
        public boolean isUserInRole(String role) { return true; }
        public boolean isSecure() { return true; }
        public String getAuthenticationScheme() { return "Bearer"; }
    };

    @BeforeAll
    static void init() throws Exception {
        ConfigManager.Initialize("dds-ui");
    }

    @SuppressWarnings("unchecked")
    private static List<JsonFolderContent> systemList() throws Exception {
        return (List<JsonFolderContent>) ENDPOINT.getSystemList(ADMIN).getEntity();
    }

    private static LibraryItem load(String uuid) throws Exception {
        return (LibraryItem) ENDPOINT.getLibraryItem(ADMIN, uuid).getEntity();
    }

    /** What SystemEdit.vue posts for "Add New": no uuids, no folder. */
    private static LibraryItem newSystem(String name) {
        LibraryItem item = new LibraryItem();
        item.setName(name);
        item.setDescription("");
        org.endeavourhealth.core.xml.QueryDocument.System system = new org.endeavourhealth.core.xml.QueryDocument.System();
        system.setName("");
        TechnicalInterface ti = new TechnicalInterface();
        ti.setName(name);
        ti.setFrequency("Daily");
        ti.setMessageType("Patient Record");
        ti.setMessageFormat("EMISCSV");
        ti.setMessageFormatVersion("5.3");
        system.getTechnicalInterface().add(ti);
        item.setSystem(system);
        return item;
    }

    @Test
    void serviceInterfaceWithDeletedSystemIsShownAsMissing() throws Exception {
        // was an NPE that turned GET /api/service into a 500
        assertEquals(ServiceEndpoint.MISSING_SYSTEM, ServiceEndpoint.findSoftwareDescForSystem(UUID.randomUUID()));
    }

    @Test
    void createFirstSystemOnEmptyDatabaseThenEditIt() throws Exception {
        int before = systemList().size();

        // unknown UUID (e.g. a stale link): 404, not an NPE
        assertThrows(NotFoundException.class, () -> load(UUID.randomUUID().toString()));

        // create
        Response saved = ENDPOINT.saveLibraryItem(ADMIN, newSystem("EMIS " + UUID.randomUUID()));
        String uuid = ((LibraryItem) saved.getEntity()).getUuid();
        assertNotNull(uuid);

        // the page reloads it straight away (router.replace to /systemEdit/edit/<uuid>)
        LibraryItem loaded = load(uuid);
        assertEquals(uuid, loaded.getUuid());
        assertEquals("", loaded.getFolderUuid());
        assertEquals(uuid, loaded.getSystem().getUuid());
        TechnicalInterface ti = loaded.getSystem().getTechnicalInterface().get(0);
        assertNotNull(ti.getUuid());
        assertEquals("EMISCSV", ti.getMessageFormat());

        // edit and save again (the loaded item is what the page posts back)
        ti.setMessageFormatVersion("5.4");
        ENDPOINT.saveLibraryItem(ADMIN, loaded);
        assertEquals("5.4", load(uuid).getSystem().getTechnicalInterface().get(0).getMessageFormatVersion());

        // the list and the formats lookup see it
        assertEquals(before + 1, systemList().size());
        @SuppressWarnings("unchecked")
        List<org.endeavourhealth.core.xml.QueryDocument.System> systems =
                (List<org.endeavourhealth.core.xml.QueryDocument.System>) ENDPOINT.getSystems(ADMIN).getEntity();
        assertTrue(systems.stream().anyMatch(s -> uuid.equals(s.getUuid())));
    }
}
