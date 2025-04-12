package cn.jia.common.handler;

import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

/**
 * @author chc
 */
@Slf4j
@Named
public class FieldFillHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("fill insert field for {} ....", metaObject.getOriginalObject().getClass());
        long time = DateUtil.nowTime();
        this.strictInsertFill(metaObject, "createTime", Long.class, time);
        this.strictInsertFill(metaObject, "updateTime", Long.class, time);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("fill update field for {} ....", metaObject.getOriginalObject().getClass());
        this.strictUpdateFill(metaObject, "updateTime", Long.class, DateUtil.nowTime());
    }
}
