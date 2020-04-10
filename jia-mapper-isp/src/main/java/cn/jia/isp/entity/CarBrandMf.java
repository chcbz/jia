package cn.jia.isp.entity;

public class CarBrandMf {
    private Integer id;

    private String clientId;

    private Integer brand;

    private String brandMf;

    private String abbr;

    private String initials;

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

    public Integer getBrand() {
        return brand;
    }

    public void setBrand(Integer brand) {
        this.brand = brand;
    }

    public String getBrandMf() {
        return brandMf;
    }

    public void setBrandMf(String brandMf) {
        this.brandMf = brandMf == null ? null : brandMf.trim();
    }

    public String getAbbr() {
        return abbr;
    }

    public void setAbbr(String abbr) {
        this.abbr = abbr == null ? null : abbr.trim();
    }

    public String getInitials() {
        return initials;
    }

    public void setInitials(String initials) {
        this.initials = initials == null ? null : initials.trim();
    }
}