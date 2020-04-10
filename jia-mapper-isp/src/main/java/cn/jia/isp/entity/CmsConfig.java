package cn.jia.isp.entity;

public class CmsConfig {
    private String clientId;

    private String tablePrefix;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix == null ? null : tablePrefix.trim();
    }
}