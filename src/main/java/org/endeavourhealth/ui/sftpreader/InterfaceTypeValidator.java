package org.endeavourhealth.ui.sftpreader;

import org.endeavourhealth.ui.json.JsonInterfaceType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * VHP: checks the body of {@code POST /api/sftpReader/interfaceTypes} before the database is touched.
 * Limits are from vhp-sftpreader's {@code schema.sql} ({@code interface_type}, {@code interface_file_type}).
 */
public final class InterfaceTypeValidator {

    static final int MAX_NAME = 1000;
    static final int MAX_FILE_TYPE = 1000;

    private InterfaceTypeValidator() {
    }

    /** A trimmed copy of the request, or a 400. */
    public static JsonInterfaceType validate(JsonInterfaceType in) throws FeedConfigException {
        if (in == null) {
            throw FeedConfigException.badRequest("No interface type was sent");
        }
        String name = FeedValidator.trimToNull(in.getInterfaceTypeName());
        if (name == null) {
            throw FeedConfigException.badRequest("Interface type name is required");
        }
        FeedValidator.maxLength("Interface type name", name, MAX_NAME);

        Integer days = in.getDataFrequencyDays();
        if (days == null || days < 1) {
            throw FeedConfigException.badRequest("Data frequency (days) must be a whole number of 1 or more");
        }

        Integer id = in.getInterfaceTypeId();
        if (id != null && id < 1) {
            throw FeedConfigException.badRequest("Interface type id must be 1 or more (or left blank for the next free id)");
        }

        // the reader reads a configuration's expected file types through interface_file_type; with none, its feeds
        // fail batch validation, so at least one is required
        List<String> fileTypes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (in.getFileTypes() != null) {
            for (String f : in.getFileTypes()) {
                String t = FeedValidator.trimToNull(f);
                if (t == null) {
                    continue;
                }
                FeedValidator.maxLength("File type identifier", t, MAX_FILE_TYPE);
                // the table's collation is case-insensitive, so "Agreements" and "agreements" collide
                if (!seen.add(t.toLowerCase(Locale.ROOT))) {
                    throw FeedConfigException.badRequest("File type identifier '" + t + "' is listed twice");
                }
                fileTypes.add(t);
            }
        }
        if (fileTypes.isEmpty()) {
            throw FeedConfigException.badRequest("At least one file type identifier is required (the SFTP Reader"
                    + " checks received files against them)");
        }
        return new JsonInterfaceType(id, name, days, fileTypes);
    }
}
