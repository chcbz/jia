package cn.jia.chat.service;

import lombok.Data;

import java.util.Map;

@Data
public class HallAgentMailboxItem {
    private String intentId;
    private String agentId;
    private Map<String, ?> payload;
    private String status;
    private int retryCount;
    private long createTime;
    private long updateTime;
}
