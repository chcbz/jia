package cn.jia.isp.entity;

public class IspSmbVDir {
    private Integer id;

    private String clientId;

    private Integer serverId;

    private String user;

    private String name;

    private String path;

    private String available;

    private String writable;

    private String browseable;

    private String printable;

    private String comment;

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

    public Integer getServerId() {
        return serverId;
    }

    public void setServerId(Integer serverId) {
        this.serverId = serverId;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user == null ? null : user.trim();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? null : path.trim();
    }

    public String getAvailable() {
        return available;
    }

    public void setAvailable(String available) {
        this.available = available == null ? null : available.trim();
    }

    public String getWritable() {
        return writable;
    }

    public void setWritable(String writable) {
        this.writable = writable == null ? null : writable.trim();
    }

    public String getBrowseable() {
        return browseable;
    }

    public void setBrowseable(String browseable) {
        this.browseable = browseable == null ? null : browseable.trim();
    }

    public String getPrintable() {
        return printable;
    }

    public void setPrintable(String printable) {
        this.printable = printable == null ? null : printable.trim();
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? null : comment.trim();
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