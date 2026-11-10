package cn.jia.mat.mapper;

import cn.jia.mat.entity.MatPhraseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatPhraseMapper extends BaseMapper<MatPhraseEntity> {

    MatPhraseEntity selectRandom(MatPhraseEntity record);

    MatPhraseEntity selectScheduledRandom(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
            @org.apache.ibatis.annotations.Param("clientId") String clientId);
}
