package cn.jia.isp.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("isp_smb_vdir")
@Schema(name = "IspSmbVDir对象")
public class IspSmbVDirEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String clientId;

    private Long serverId;

    private String user;

    private String name;

    private String path;

    private String available;

    private String writable;

    private String browseable;

    private String printable;

    private String comment;

    private Long createTime;

    private Long updateTime;

}