package cn.jia.chat.handler.dto;

import lombok.Data;

import java.util.List;

/** Additive cancellation contract; all selectors are re-authorized by the server. */
@Data
public class ChatCancelDTO {
    private Long expectedStateVersion;
    private String reason;
    private Boolean allPending;
    private List<String> turnIds;
}
