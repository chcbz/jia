package cn.jia.common.handler;

import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class FieldFillHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("start insert fill ....");
        long time = DateUtil.genTime(new Date());
        this.strictInsertFill(metaObject, "createTime", Long.class, time);
        this.strictInsertFill(metaObject, "updateTime", Long.class, time);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("start update fill ....");
        this.strictUpdateFill(metaObject, "updateTime", Long.class, DateUtil.genTime(new Date()));
    }
}
