package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentWorkItemLeaseScanDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer scannedCount = 0;
    private Integer expiredCount = 0;
    private Integer requeuedCount = 0;
    private Integer failedCount = 0;
    private Integer conflictCount = 0;
    private List<AgentWorkItemLeaseDTO> transitions = new ArrayList<>();
}
