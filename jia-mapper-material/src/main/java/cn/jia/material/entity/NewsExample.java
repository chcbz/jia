package cn.jia.material.entity;

public class NewsExample extends News {

    private Integer clientStrictFlag;

    private Long createTimeStart;

    private Long createTimeEnd;

    private Long updateTimeStart;

    private Long updateTimeEnd;

    public Integer getClientStrictFlag() {
        return clientStrictFlag;
    }

    public void setClientStrictFlag(Integer clientStrictFlag) {
        this.clientStrictFlag = clientStrictFlag;
    }

    public Long getCreateTimeStart() {
        return createTimeStart;
    }

    public void setCreateTimeStart(Long createTimeStart) {
        this.createTimeStart = createTimeStart;
    }

    public Long getCreateTimeEnd() {
        return createTimeEnd;
    }

    public void setCreateTimeEnd(Long createTimeEnd) {
        this.createTimeEnd = createTimeEnd;
    }

    public Long getUpdateTimeStart() {
        return updateTimeStart;
    }

    public void setUpdateTimeStart(Long updateTimeStart) {
        this.updateTimeStart = updateTimeStart;
    }

    public Long getUpdateTimeEnd() {
        return updateTimeEnd;
    }

    public void setUpdateTimeEnd(Long updateTimeEnd) {
        this.updateTimeEnd = updateTimeEnd;
    }
}