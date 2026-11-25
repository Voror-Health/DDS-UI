package org.endeavourhealth.ui.json;

/**
 * VHP: the body of {@code PUT /api/sftpReader/feeds/{configurationId}}: the Add Feed fields (the configuration id comes
 * from the URL and {@code createInstance} is ignored; leaving a private key or password empty keeps the stored one),
 * plus the {@code version} the edit form was loaded with. No {@code toString()}: it can hold keys and passwords.
 */
public class JsonEditFeedRequest extends JsonAddFeedRequest {
    private String version;

    public JsonEditFeedRequest() {
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
