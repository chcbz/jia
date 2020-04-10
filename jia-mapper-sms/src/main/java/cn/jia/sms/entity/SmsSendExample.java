package cn.jia.sms.entity;

public class SmsSendExample extends SmsSend {

    private Long timeStart;

    private Long timeEnd;


    public Long getTimeEnd() {
        return timeEnd;
    }

    public void setTimeEnd(Long timeEnd) {
        this.timeEnd = timeEnd;
    }

    public Long getTimeStart() {
        return timeStart;
    }

    public void setTimeStart(Long timeStart) {
        this.timeStart = timeStart;
    }
}
