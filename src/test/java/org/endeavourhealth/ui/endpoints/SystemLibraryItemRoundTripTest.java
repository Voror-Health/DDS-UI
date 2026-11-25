package org.endeavourhealth.ui.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.endeavourhealth.core.xml.QueryDocument.LibraryItem;
import org.endeavourhealth.core.xml.QueryDocument.QueryDocument;
import org.endeavourhealth.core.xml.QueryDocumentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The System page's save -> load round trip through QueryDocument.xsd, minus the database.
 * LibraryEndpoint.saveLibraryItem turns a null folderUuid into "" because the XSD requires the element.
 */
class SystemLibraryItemRoundTripTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** What SystemEdit.vue posts for a new system ("Add New": no uuids, no folder). */
    private static LibraryItem newSystemFromSpa() throws Exception {
        String json = "{\"uuid\":null,\"name\":\"EMIS\",\"description\":\"\",\"folderUuid\":null,"
                + "\"system\":{\"uuid\":\"a682668e-0ba8-41ed-8b33-4a7ca88fb14c\",\"name\":\"EMIS\",\"technicalInterface\":[{"
                + "\"uuid\":\"b682668e-0ba8-41ed-8b33-4a7ca88fb14c\",\"name\":\"EMIS\",\"frequency\":\"Daily\","
                + "\"messageType\":\"Patient Record\",\"messageFormat\":\"EMISCSV\",\"messageFormatVersion\":\"5.3\"}]}}";
        LibraryItem item = OM.readValue(json, LibraryItem.class);
        item.setUuid("a682668e-0ba8-41ed-8b33-4a7ca88fb14c"); // set by saveLibraryItem on insert
        return item;
    }

    private static String toXml(LibraryItem item) {
        QueryDocument doc = new QueryDocument();
        doc.getLibraryItem().add(item);
        return QueryDocumentSerializer.writeToXml(doc);
    }

    @Test
    void nullFolderFailsSchemaValidation() throws Exception {
        LibraryItem item = newSystemFromSpa();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> toXml(item));
        assertTrue(String.valueOf(ex.getCause()).contains("MarshalException"), ex.toString());
    }

    @Test
    void emptyFolderSavesAndLoadsBack() throws Exception {
        LibraryItem item = newSystemFromSpa();
        item.setFolderUuid(""); // what saveLibraryItem now does

        LibraryItem back = QueryDocumentSerializer.readLibraryItemFromXml(toXml(item));

        assertEquals("", back.getFolderUuid());
        assertEquals("EMISCSV", back.getSystem().getTechnicalInterface().get(0).getMessageFormat());
        assertTrue(OM.writeValueAsString(back).contains("\"messageFormatVersion\":\"5.3\""));
    }
}
