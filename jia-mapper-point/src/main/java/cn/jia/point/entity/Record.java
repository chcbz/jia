package cn.jia.point.entity;

public class Record {
    private Integer id;

    private String jiacn;

    private Integer type;

    private Integer change;

    private Integer remain;

    private Long time;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getJiacn() {
        return jiacn;
    }

    public void setJiacn(String jiacn) {
        this.jiacn = jiacn == null ? null : jiacn.trim();
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getChange() {
        return change;
    }

    public void setChange(Integer change) {
        this.change = change;
    }

    public Integer getRemain() {
        return remain;
    }

    public void setRemain(Integer remain) {
        this.remain = remain;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }
}