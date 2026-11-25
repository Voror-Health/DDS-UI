package org.endeavourhealth.ui.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * VHP: one entry of the {@code global / message_formats} config row (see {@code MessageFormats}).
 *
 * <ul>
 *   <li>{@code format}: the value a System's technical interface {@code messageFormat} and an SFTP Reader feed's
 *       {@code software_content_type} must hold (the Messaging API's {@code SourceSystem} header)</li>
 *   <li>{@code sftpReader}: vhp-sftpreader's {@code ImplementationActivator} can read feeds of this format</li>
 *   <li>{@code transform}: the inbound pipeline ({@code MessageTransformInbound}) has a transform for it</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonMessageFormat {
    private String format;
    private String description;
    private boolean sftpReader;
    private boolean transform;

    public JsonMessageFormat() {
    }

    public JsonMessageFormat(String format, String description, boolean sftpReader, boolean transform) {
        this.format = format;
        this.description = description;
        this.sftpReader = sftpReader;
        this.transform = transform;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSftpReader() {
        return sftpReader;
    }

    public void setSftpReader(boolean sftpReader) {
        this.sftpReader = sftpReader;
    }

    public boolean isTransform() {
        return transform;
    }

    public void setTransform(boolean transform) {
        this.transform = transform;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JsonMessageFormat that)) {
            return false;
        }
        return sftpReader == that.sftpReader && transform == that.transform
                && Objects.equals(format, that.format) && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, description, sftpReader, transform);
    }

    @Override
    public String toString() {
        return format + " (sftpReader=" + sftpReader + ", transform=" + transform + ")";
    }
}
