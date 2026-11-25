package org.endeavourhealth.ui.messageformats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.endeavourhealth.ui.json.JsonMessageFormat;
import org.endeavourhealth.ui.sftpreader.FeedConfigException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageFormatsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** an in-memory config row */
    static final class FakeStore implements MessageFormats.Store {
        String row;
        RuntimeException readFailure;
        int writes;

        FakeStore(String row) {
            this.row = row;
        }

        @Override
        public String read() {
            if (readFailure != null) {
                throw readFailure;
            }
            return row;
        }

        @Override
        public void write(String json) {
            row = json;
            writes++;
        }
    }

    @AfterEach
    void restore() {
        MessageFormats.setStore(null);
    }

    private static FakeStore install(String row) {
        FakeStore s = new FakeStore(row);
        MessageFormats.setStore(s);
        return s;
    }

    private static List<JsonMessageFormat> parse(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<List<JsonMessageFormat>>() {
        });
    }

    @Test
    void noRowGivesTheDefaults() {
        install(null);
        MessageFormats.Snapshot s = MessageFormats.load();
        assertEquals("default", s.source);
        assertTrue(s.warning.contains("no global/message_formats config row"), s.warning);
        assertEquals(MessageFormats.DEFAULTS, s.formats);
    }

    @Test
    void unreadableConfigDbGivesTheDefaults() {
        install(null).readFailure = new IllegalStateException("connection refused");
        MessageFormats.Snapshot s = MessageFormats.load();
        assertEquals("default", s.source);
        assertEquals(MessageFormats.DEFAULTS, s.formats);
    }

    @Test
    void theRowIsUsedWhenPresent() {
        install("[{\"format\":\"EMISCSV\",\"description\":\"d\",\"sftpReader\":true,\"transform\":true},"
                + "{\"format\":\"HL7V2\",\"sftpReader\":false,\"transform\":true,\"someFutureField\":1}]");
        MessageFormats.Snapshot s = MessageFormats.load();
        assertEquals("config", s.source);
        assertNull(s.warning);
        assertEquals(List.of("EMISCSV", "HL7V2"), s.formats.stream().map(JsonMessageFormat::getFormat).toList());
        assertEquals(List.of("EMISCSV"), MessageFormats.sftpReaderFormats());
    }

    @Test
    void anInvalidRowFallsBackAndIsNotOverwritten() throws Exception {
        for (String bad : new String[]{"{not json", "{\"format\":\"EMISCSV\"}", "[{\"format\":\"emis csv\"}]",
                "[{\"format\":\"EMISCSV\"},{\"format\":\"EMISCSV\"}]", "[null]"}) {
            FakeStore store = install(bad);
            MessageFormats.Snapshot s = MessageFormats.load();
            assertEquals("default", s.source, bad);
            assertTrue(s.warning.contains("not valid"), bad + ": " + s.warning);
            FeedConfigException ex = assertThrows(FeedConfigException.class,
                    () -> MessageFormats.add(new JsonMessageFormat("NEWFMT", "", false, true)), bad);
            assertEquals(409, ex.getStatus());
            assertEquals(0, store.writes, bad);
            assertEquals(bad, store.row);
        }
    }

    @Test
    void addingWithNoRowWritesTheDefaultsPlusTheNewFormat() throws Exception {
        FakeStore store = install(null);
        MessageFormats.Snapshot s = MessageFormats.add(new JsonMessageFormat("  newfmt ", "  New thing ", true, false));
        assertEquals("config", s.source);
        assertEquals(1, store.writes);
        List<JsonMessageFormat> written = parse(store.row);
        assertEquals(MessageFormats.DEFAULTS.size() + 1, written.size());
        assertEquals(MessageFormats.DEFAULTS, written.subList(0, MessageFormats.DEFAULTS.size()));
        assertEquals(new JsonMessageFormat("NEWFMT", "New thing", true, false), written.get(written.size() - 1));
        assertTrue(MessageFormats.sftpReaderFormats().contains("NEWFMT"));
    }

    @Test
    void addingKeepsWhatIsInTheRow() throws Exception {
        FakeStore store = install("[{\"format\":\"EMISCSV\",\"description\":\"mine\",\"sftpReader\":true,\"transform\":true}]");
        MessageFormats.add(new JsonMessageFormat("NEWFMT", null, false, true));
        assertEquals(List.of(new JsonMessageFormat("EMISCSV", "mine", true, true), new JsonMessageFormat("NEWFMT", "", false, true)),
                parse(store.row));
    }

    @Test
    void badAdditionsAreRefused() {
        install(null);
        Object[][] cases = {
                {null, 400, "No message format"},
                {new JsonMessageFormat(" ", "", true, true), 400, "required"},
                {new JsonMessageFormat("EMIS CSV", "", true, true), 400, "may only contain"},
                {new JsonMessageFormat("EMIS-CSV", "", true, true), 400, "may only contain"},
                {new JsonMessageFormat("X".repeat(101), "", true, true), 400, "longer than"},
                {new JsonMessageFormat("NEWFMT", "d".repeat(201), true, true), 400, "longer than"},
                {new JsonMessageFormat("NEWFMT", "", false, false), 400, "or both"},
                {new JsonMessageFormat("emiscsv", "", true, true), 409, "already listed"},
        };
        for (Object[] c : cases) {
            FeedConfigException ex = assertThrows(FeedConfigException.class, () -> MessageFormats.add((JsonMessageFormat) c[0]),
                    String.valueOf(c[0]));
            assertEquals(c[1], ex.getStatus(), ex.getMessage());
            assertTrue(ex.getMessage().contains((String) c[2]), ex.getMessage());
        }
    }

    @Test
    void defaultsMatchTheCode() {
        // vhp-sftpreader ImplementationActivator and eds-messaging-core MessageTransformInbound, 2026-11-25
        assertEquals(List.of("EMISCSV", "TPPCSV", "VISIONCSV", "BARTSCSV", "HOMERTONCSV", "ADASTRACSV", "BHRUTCSV", "IMPERIALCSV", "NEMSXML"),
                MessageFormats.DEFAULTS.stream().filter(JsonMessageFormat::isSftpReader).map(JsonMessageFormat::getFormat).toList());
        assertTrue(MessageFormats.DEFAULTS.stream().allMatch(JsonMessageFormat::isTransform));
        assertEquals(15, MessageFormats.DEFAULTS.size());
    }

    @Test
    void theSeedScriptHoldsTheDefaults() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/2026-11-25_message_formats.sql"));
        int start = sql.indexOf("'message_formats', '[") + "'message_formats', '".length();
        int end = sql.indexOf("]'", start) + 1;
        assertTrue(start > 20 && end > start, "seed JSON not found");
        String json = sql.substring(start, end);
        assertFalse(json.contains("'"), "a quote in the JSON would end the SQL string");
        assertEquals(MessageFormats.DEFAULTS, parse(json));
    }
}
