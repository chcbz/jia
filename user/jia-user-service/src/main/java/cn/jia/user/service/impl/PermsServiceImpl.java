package cn.jia.user.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.user.dao.UserPermsDao;
import cn.jia.user.dao.UserPermsRelDao;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.service.PermsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PermsServiceImpl extends BaseServiceImpl<UserPermsDao, PermsEntity> implements PermsService {
    @Autowired
    private UserPermsRelDao userPermsRelDao;

    @Override
    public List<PermsEntity> findByUserId(Long userId) {
        return userPermsRelDao.selectByUserId(userId).stream().map(entity -> {
            PermsEntity permsEntity = new PermsEntity();
            permsEntity.setId(entity.getId());
            return permsEntity;
        }).toList();
    }

    @Override
    public List<PermsEntity> findByRoleId(Long roleId) {
        return userPermsRelDao.selectByRoleId(roleId).stream().map(entity -> {
            PermsEntity permsEntity = new PermsEntity();
            permsEntity.setId(entity.getId());
            return permsEntity;
        }).toList();
    }

    @Override
    @Transactional
    public void refresh(List<PermsEntity> permsEntityList) {
        List<PermsEntity> moduleResource = baseDao.selectAll();
        Long now = DateUtil.nowTime();
        //失效模块所有权限
        for(PermsEntity p : moduleResource) {
            if(p.getSource().equals(1)) {
                p.setStatus(EsConstants.PERMS_STATUS_DISABLE);
                p.setUpdateTime(now);
                baseDao.updateById(p);
            }
        }
        //新增权限
        for(PermsEntity action: permsEntityList) {
            boolean exist = false;
            for(PermsEntity mp : moduleResource) {
                //如果该权限已存在，则重新生效
                if (action.getModule().equals(mp.getModule()) && action.getFunc().equals(mp.getFunc())) {
                    mp.setStatus(EsConstants.PERMS_STATUS_ENABLE);
                    mp.setUpdateTime(now);
                    baseDao.updateById(mp);
                    exist = true;
                }
            }
            //如果该权限不存在，则新增权限
            if(!exist) {
                action.setStatus(EsConstants.PERMS_STATUS_ENABLE);
                action.setCreateTime(now);
                action.setUpdateTime(now);
                baseDao.insert(action);
            }
        }
    }
}
