package cn.jia.isp.entity;

public class IspDomain {
    private Integer no;

    private String clientId;

    private Integer serverId;

    private String domainName;

    private String dnsType;

    private String dnsKey;

    private String dnsToken;

    private Integer sslFlag;

    private String adminPasswd;

    private Integer adminFlag;

    private Integer mailboxService;

    private Integer mailboxCount;

    private Integer mailboxQuota;

    private Integer hostService;

    private String hostType;

    private String hostPasswd;

    private Integer hostQuota;

    private Integer sqlService;

    private String sqlPasswd;

    private Integer sqlQuota;

    private String ftpDir;

    private Integer cmsFlag;

    public Integer getNo() {
        return no;
    }

    public void setNo(Integer no) {
        this.no = no;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

    public Integer getServerId() {
        return serverId;
    }

    public void setServerId(Integer serverId) {
        this.serverId = serverId;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName == null ? null : domainName.trim();
    }

    public String getDnsType() {
        return dnsType;
    }

    public void setDnsType(String dnsType) {
        this.dnsType = dnsType == null ? null : dnsType.trim();
    }

    public String getDnsKey() {
        return dnsKey;
    }

    public void setDnsKey(String dnsKey) {
        this.dnsKey = dnsKey == null ? null : dnsKey.trim();
    }

    public String getDnsToken() {
        return dnsToken;
    }

    public void setDnsToken(String dnsToken) {
        this.dnsToken = dnsToken == null ? null : dnsToken.trim();
    }

    public Integer getSslFlag() {
        return sslFlag;
    }

    public void setSslFlag(Integer sslFlag) {
        this.sslFlag = sslFlag;
    }

    public String getAdminPasswd() {
        return adminPasswd;
    }

    public void setAdminPasswd(String adminPasswd) {
        this.adminPasswd = adminPasswd == null ? null : adminPasswd.trim();
    }

    public Integer getAdminFlag() {
        return adminFlag;
    }

    public void setAdminFlag(Integer adminFlag) {
        this.adminFlag = adminFlag;
    }

    public Integer getMailboxService() {
        return mailboxService;
    }

    public void setMailboxService(Integer mailboxService) {
        this.mailboxService = mailboxService;
    }

    public Integer getMailboxCount() {
        return mailboxCount;
    }

    public void setMailboxCount(Integer mailboxCount) {
        this.mailboxCount = mailboxCount;
    }

    public Integer getMailboxQuota() {
        return mailboxQuota;
    }

    public void setMailboxQuota(Integer mailboxQuota) {
        this.mailboxQuota = mailboxQuota;
    }

    public Integer getHostService() {
        return hostService;
    }

    public void setHostService(Integer hostService) {
        this.hostService = hostService;
    }

    public String getHostType() {
        return hostType;
    }

    public void setHostType(String hostType) {
        this.hostType = hostType == null ? null : hostType.trim();
    }

    public String getHostPasswd() {
        return hostPasswd;
    }

    public void setHostPasswd(String hostPasswd) {
        this.hostPasswd = hostPasswd == null ? null : hostPasswd.trim();
    }

    public Integer getHostQuota() {
        return hostQuota;
    }

    public void setHostQuota(Integer hostQuota) {
        this.hostQuota = hostQuota;
    }

    public Integer getSqlService() {
        return sqlService;
    }

    public void setSqlService(Integer sqlService) {
        this.sqlService = sqlService;
    }

    public String getSqlPasswd() {
        return sqlPasswd;
    }

    public void setSqlPasswd(String sqlPasswd) {
        this.sqlPasswd = sqlPasswd == null ? null : sqlPasswd.trim();
    }

    public Integer getSqlQuota() {
        return sqlQuota;
    }

    public void setSqlQuota(Integer sqlQuota) {
        this.sqlQuota = sqlQuota;
    }

    public String getFtpDir() {
        return ftpDir;
    }

    public void setFtpDir(String ftpDir) {
        this.ftpDir = ftpDir == null ? null : ftpDir.trim();
    }

    public Integer getCmsFlag() {
        return cmsFlag;
    }

    public void setCmsFlag(Integer cmsFlag) {
        this.cmsFlag = cmsFlag;
    }
}