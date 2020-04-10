package cn.jia.isp.entity;

public class CarBrandVersion {
    private Integer id;

    private String clientId;

    private Integer audi;

    private String version;

    private String vName;

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

    public Integer getAudi() {
        return audi;
    }

    public void setAudi(Integer audi) {
        this.audi = audi;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? null : version.trim();
    }

    public String getvName() {
        return vName;
    }

    public void setvName(String vName) {
        this.vName = vName == null ? null : vName.trim();
    }
}