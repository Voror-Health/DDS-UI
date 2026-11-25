package org.endeavourhealth.ui.json;

import java.util.ArrayList;
import java.util.List;

/**
 * VHP: one row of the SFTP Reader's {@code interface_type} table with its {@code interface_file_type} identifiers.
 * Returned by {@code GET /api/sftpReader/interfaceTypes} and posted to {@code POST /api/sftpReader/interfaceTypes}
 * (where {@code interfaceTypeId} may be left out for the next free id).
 */
public class JsonInterfaceType {
    private Integer interfaceTypeId;
    private String interfaceTypeName;
    private Integer dataFrequencyDays;
    private List<String> fileTypes = new ArrayList<>();
    /** read-only: how many configurations (feeds) use this type; a type can only be deleted when this is 0 */
    private Integer configurationCount;

    public JsonInterfaceType() {
    }

    public JsonInterfaceType(Integer interfaceTypeId, String interfaceTypeName, Integer dataFrequencyDays, List<String> fileTypes) {
        this.interfaceTypeId = interfaceTypeId;
        this.interfaceTypeName = interfaceTypeName;
        this.dataFrequencyDays = dataFrequencyDays;
        this.fileTypes = fileTypes;
    }

    public Integer getInterfaceTypeId() {
        return interfaceTypeId;
    }

    public void setInterfaceTypeId(Integer interfaceTypeId) {
        this.interfaceTypeId = interfaceTypeId;
    }

    public String getInterfaceTypeName() {
        return interfaceTypeName;
    }

    public void setInterfaceTypeName(String interfaceTypeName) {
        this.interfaceTypeName = interfaceTypeName;
    }

    public Integer getDataFrequencyDays() {
        return dataFrequencyDays;
    }

    public void setDataFrequencyDays(Integer dataFrequencyDays) {
        this.dataFrequencyDays = dataFrequencyDays;
    }

    public List<String> getFileTypes() {
        return fileTypes;
    }

    public void setFileTypes(List<String> fileTypes) {
        this.fileTypes = fileTypes;
    }

    public Integer getConfigurationCount() {
        return configurationCount;
    }

    public void setConfigurationCount(Integer configurationCount) {
        this.configurationCount = configurationCount;
    }
}
