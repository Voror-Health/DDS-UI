package org.endeavourhealth.ui.json;

/**
 * VHP: the body of {@code POST /api/sftpReader/addFeed} (the SFTP Reader page's Add Feed dialog): one new SFTP Reader
 * configuration with its SFTP, PGP and optional key/value rows. Holds keys and passwords, so it deliberately has no
 * {@code toString()}; never log it.
 */
public class JsonAddFeedRequest {
    private String instanceName;
    private Boolean createInstance;
    private String configurationId;
    private Integer interfaceTypeId;
    private Integer pollFrequencySeconds;
    private String localRootPath;
    private String friendlyName;
    private String softwareContentType;
    private String softwareVersion;
    private String hostname;
    private Integer port;
    private String remotePath;
    private String username;
    private String clientPublicKey;
    private String clientPrivateKey;
    private String clientPrivateKeyPassword;
    private String hostPublicKey;
    private String fileExtensionFilter;
    private String senderPublicKey;
    private String recipientPublicKey;
    private String recipientPrivateKey;
    private String recipientPrivateKeyPassword;
    private String sharingAgreementGuid;

    public JsonAddFeedRequest() {
    }

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public Boolean getCreateInstance() {
        return createInstance;
    }

    public void setCreateInstance(Boolean createInstance) {
        this.createInstance = createInstance;
    }

    public String getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(String configurationId) {
        this.configurationId = configurationId;
    }

    public Integer getInterfaceTypeId() {
        return interfaceTypeId;
    }

    public void setInterfaceTypeId(Integer interfaceTypeId) {
        this.interfaceTypeId = interfaceTypeId;
    }

    public Integer getPollFrequencySeconds() {
        return pollFrequencySeconds;
    }

    public void setPollFrequencySeconds(Integer pollFrequencySeconds) {
        this.pollFrequencySeconds = pollFrequencySeconds;
    }

    public String getLocalRootPath() {
        return localRootPath;
    }

    public void setLocalRootPath(String localRootPath) {
        this.localRootPath = localRootPath;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public void setFriendlyName(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String getSoftwareContentType() {
        return softwareContentType;
    }

    public void setSoftwareContentType(String softwareContentType) {
        this.softwareContentType = softwareContentType;
    }

    public String getSoftwareVersion() {
        return softwareVersion;
    }

    public void setSoftwareVersion(String softwareVersion) {
        this.softwareVersion = softwareVersion;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getClientPublicKey() {
        return clientPublicKey;
    }

    public void setClientPublicKey(String clientPublicKey) {
        this.clientPublicKey = clientPublicKey;
    }

    public String getClientPrivateKey() {
        return clientPrivateKey;
    }

    public void setClientPrivateKey(String clientPrivateKey) {
        this.clientPrivateKey = clientPrivateKey;
    }

    public String getClientPrivateKeyPassword() {
        return clientPrivateKeyPassword;
    }

    public void setClientPrivateKeyPassword(String clientPrivateKeyPassword) {
        this.clientPrivateKeyPassword = clientPrivateKeyPassword;
    }

    public String getHostPublicKey() {
        return hostPublicKey;
    }

    public void setHostPublicKey(String hostPublicKey) {
        this.hostPublicKey = hostPublicKey;
    }

    public String getFileExtensionFilter() {
        return fileExtensionFilter;
    }

    public void setFileExtensionFilter(String fileExtensionFilter) {
        this.fileExtensionFilter = fileExtensionFilter;
    }

    public String getSenderPublicKey() {
        return senderPublicKey;
    }

    public void setSenderPublicKey(String senderPublicKey) {
        this.senderPublicKey = senderPublicKey;
    }

    public String getRecipientPublicKey() {
        return recipientPublicKey;
    }

    public void setRecipientPublicKey(String recipientPublicKey) {
        this.recipientPublicKey = recipientPublicKey;
    }

    public String getRecipientPrivateKey() {
        return recipientPrivateKey;
    }

    public void setRecipientPrivateKey(String recipientPrivateKey) {
        this.recipientPrivateKey = recipientPrivateKey;
    }

    public String getRecipientPrivateKeyPassword() {
        return recipientPrivateKeyPassword;
    }

    public void setRecipientPrivateKeyPassword(String recipientPrivateKeyPassword) {
        this.recipientPrivateKeyPassword = recipientPrivateKeyPassword;
    }

    public String getSharingAgreementGuid() {
        return sharingAgreementGuid;
    }

    public void setSharingAgreementGuid(String sharingAgreementGuid) {
        this.sharingAgreementGuid = sharingAgreementGuid;
    }
}
