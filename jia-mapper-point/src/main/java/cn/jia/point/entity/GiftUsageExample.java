package cn.jia.point.entity;

public class GiftUsageExample extends GiftUsage {

    private Integer clientStrictFlag;

    private Long timeStart;

    private Long timeEnd;

    public Integer getClientStrictFlag() {
        return clientStrictFlag;
    }

    public void setClientStrictFlag(Integer clientStrictFlag) {
        this.clientStrictFlag = clientStrictFlag;
    }

    public Long getTimeStart() {
        return timeStart;
    }

    public void setTimeStart(Long timeStart) {
        this.timeStart = timeStart;
    }

    public Long getTimeEnd() {
        return timeEnd;
    }

    public void setTimeEnd(Long timeEnd) {
        this.timeEnd = timeEnd;
    }
}