package cn.jia.core.entity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class DelayObj implements Delayed {
    /** 延迟时间，毫秒保存 */
    private long delayTime;
    /** 要执行的任务 */
    private String data;

    public DelayObj(long delayTime, String data) {
        this.delayTime = delayTime + System.currentTimeMillis();
        this.data = data;
    }

    /**
     * 获取剩余过期时间
     *
     * @param unit 时间单位
     * @return 剩余过期时间
     */
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(this.delayTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return (int) (this.getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public String toString() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String date =  dateTimeFormatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(delayTime), ZoneId.systemDefault()));
        return "DelayObj{" +
                "delayTime=" + date +
                ", data='" + data + '\'' +
                '}';
    }

    public long getDelayTime() {
        return delayTime;
    }

    public void setDelayTime(long delayTime) {
        this.delayTime = delayTime;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
