package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** Owner-scoped private Hall case metadata; execution state remains authoritative elsewhere. */
@Data
@Accessors(chain = true)
@TableName("hall_private_case")
public class HallPrivateCaseEntity implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @TableId(value = "case_id", type = IdType.INPUT)
    private String caseId;
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String title;
    private String originRef;
    private Long revision;
    private Long createdAt;
    private Long updatedAt;
}
