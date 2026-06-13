package cn.jia.chat.service;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HallActionDispatchResult {
    private String intentId;
    private String agentId;
    private String status;
    private String message;
}
