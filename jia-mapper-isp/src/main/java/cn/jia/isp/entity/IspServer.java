package cn.jia.isp.entity;

public class IspServer {
    private Integer id;

    private String clientId;

    private String serverName;

    private String serverDescription;

    private String ip;

    private Integer sshPort;

    private String sshUser;

    private String sshPassword;

    private Integer consolePort;

    private String consoleToken;

    private Integer ldapService;

    private Integer ldapPort;

    private String ldapUser;

    private String ldapPassword;

    private String ldapBase;

    private Integer smbService;

    private String smbLdapBase;

    private String smbLdapUser;

    private String smbLdapPassword;

    private String smbLdapUrl;

    private Integer status;

    private Long createTime;

    private Long updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName == null ? null : serverName.trim();
    }

    public String getServerDescription() {
        return serverDescription;
    }

    public void setServerDescription(String serverDescription) {
        this.serverDescription = serverDescription == null ? null : serverDescription.trim();
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip == null ? null : ip.trim();
    }

    public Integer getSshPort() {
        return sshPort;
    }

    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser == null ? null : sshUser.trim();
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword == null ? null : sshPassword.trim();
    }

    public Integer getConsolePort() {
        return consolePort;
    }

    public void setConsolePort(Integer consolePort) {
        this.consolePort = consolePort;
    }

    public String getConsoleToken() {
        return consoleToken;
    }

    public void setConsoleToken(String consoleToken) {
        this.consoleToken = consoleToken == null ? null : consoleToken.trim();
    }

    public Integer getLdapService() {
        return ldapService;
    }

    public void setLdapService(Integer ldapService) {
        this.ldapService = ldapService;
    }

    public Integer getLdapPort() {
        return ldapPort;
    }

    public void setLdapPort(Integer ldapPort) {
        this.ldapPort = ldapPort;
    }

    public String getLdapUser() {
        return ldapUser;
    }

    public void setLdapUser(String ldapUser) {
        this.ldapUser = ldapUser == null ? null : ldapUser.trim();
    }

    public String getLdapPassword() {
        return ldapPassword;
    }

    public void setLdapPassword(String ldapPassword) {
        this.ldapPassword = ldapPassword == null ? null : ldapPassword.trim();
    }

    public String getLdapBase() {
        return ldapBase;
    }

    public void setLdapBase(String ldapBase) {
        this.ldapBase = ldapBase == null ? null : ldapBase.trim();
    }

    public Integer getSmbService() {
        return smbService;
    }

    public void setSmbService(Integer smbService) {
        this.smbService = smbService;
    }

    public String getSmbLdapBase() {
        return smbLdapBase;
    }

    public void setSmbLdapBase(String smbLdapBase) {
        this.smbLdapBase = smbLdapBase == null ? null : smbLdapBase.trim();
    }

    public String getSmbLdapUser() {
        return smbLdapUser;
    }

    public void setSmbLdapUser(String smbLdapUser) {
        this.smbLdapUser = smbLdapUser == null ? null : smbLdapUser.trim();
    }

    public String getSmbLdapPassword() {
        return smbLdapPassword;
    }

    public void setSmbLdapPassword(String smbLdapPassword) {
        this.smbLdapPassword = smbLdapPassword == null ? null : smbLdapPassword.trim();
    }

    public String getSmbLdapUrl() {
        return smbLdapUrl;
    }

    public void setSmbLdapUrl(String smbLdapUrl) {
        this.smbLdapUrl = smbLdapUrl == null ? null : smbLdapUrl.trim();
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}