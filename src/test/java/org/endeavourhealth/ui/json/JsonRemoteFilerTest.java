package org.endeavourhealth.ui.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRemoteFilerTest {

    @Test
    void sftpKeyPasswordIsReadFromConfigButNeverSentToTheBrowser() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String config = "{\"sftpConnectionDetails\":{\"hostname\":\"sftp.example\",\"username\":\"u\","
                + "\"clientPrivateKeyPassword\":\"pw-123\"}}";

        JsonRemoteFiler.JsonTechnicalDetails details = mapper.readValue(config, JsonRemoteFiler.JsonTechnicalDetails.class);
        assertEquals("pw-123", details.getSftpConnectionDetails().getClientPrivateKeyPassword());

        JsonRemoteFiler filer = new JsonRemoteFiler();
        filer.setTechnicalDetails(details);
        String out = mapper.writeValueAsString(filer);
        assertTrue(out.contains("sftp.example"), out);
        assertFalse(out.contains("pw-123"), out);
        assertFalse(out.contains("clientPrivateKeyPassword"), out);
    }
}
