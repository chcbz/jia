package cn.jia.agent.output.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("output_access_ticket")
public class OutputAccessTicketEntity {
    private String tenantId;
    private String clientId;
    @TableId(value = "ticket_hash", type = IdType.INPUT)
    private byte[] ticketHash;
    private String runId;
    private String bindingId;
    private String issuedRuntimeId;
    private String operationsJson;
    private Long expiresAt;
    private Long revokedAt;
    private Long createdAt;
    private Long updatedAt;
    private Long rowVersion;
}
