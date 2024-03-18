package cn.jia.mat.mapper;

import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.entity.MatNewsReqVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.pagehelper.Page;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatNewsMapper extends BaseMapper<MatNewsEntity> {

    Page<MatNewsEntity> selectByExample(MatNewsReqVO example);
}
