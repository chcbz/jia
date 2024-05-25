package cn.jia.wx.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.*;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.FileService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.user.common.UserConstants;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MpUserServiceImpl extends BaseServiceImpl<MpUserDao, MpUserEntity> implements MpUserService {
	@Autowired(required = false)
	private LdapUserService ldapUserService;
	@Autowired(required = false)
	private UserService userService;
	@Autowired(required = false)
	private FileService fileService;

	@Override
	public MpUserEntity create(MpUserEntity user) {
		// 将用户添加到ldap服务器
		LdapUser params = new LdapUser();
		params.setCn(user.getOpenId());
		params.setSn(user.getOpenId());
		params.setEmail(StringUtil.isEmpty(user.getEmail()) ? null : user.getEmail());
		params.setOpenid(user.getOpenId());
		params.setCountry(StringUtil.isEmpty(user.getCountry()) ? null : user.getCountry());
		params.setProvince(StringUtil.isEmpty(user.getProvince()) ? null : user.getProvince());
		params.setCity(StringUtil.isEmpty(user.getCity()) ? null : user.getCity());
		params.setSex(user.getSex());
		params.setNickname(StringUtil.isEmpty(user.getNickname()) ? null : user.getNickname());
		if(StringUtil.isNotEmpty(user.getHeadImgUrl())) {
			params.setHeadimg(ImgUtil.fromUrl(user.getHeadImgUrl()));
		}
		ldapUserService.create(params);
		// 保存系统用户
		UserEntity u = new UserEntity();
		BeanUtil.copyPropertiesIgnoreNull(user, u);
		u.setJiacn(params.getUid());
		u.setOpenid(user.getOpenId());

		String filename = DateUtil.getDateString() + "_" + user.getOpenId() + ".jpg";
		String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
		byte[] b = null;
		if (StringUtil.isNotEmpty(user.getHeadImgUrl())) {
			b = ImgUtil.fromUrl(user.getHeadImgUrl());
		}
		if (b != null) {
			FileUtil.create(b, filePath + "/avatar/" + filename);

			//保存文件信息
			IspFileEntity cf = new IspFileEntity();
			cf.setExtension(FileUtil.getExtension(filename));
			cf.setName(user.getOpenId() + ".jpg");
			cf.setSize((long) b.length);
			cf.setType(EsConstants.FILE_TYPE_AVATAR);
			cf.setUri("avatar/" + filename);
			fileService.create(cf);

			u.setAvatar("avatar/" + filename);
		}
		userService.create(u);
		// 保存微信用户
		user.setJiacn(params.getUid());
		user.setStatus(UserConstants.COMMON_ENABLE);
		baseDao.insert(user);
		return user;
	}

	@Override
	public MpUserEntity findByOpenId(String openId) {
		MpUserEntity mpUserEntity = new MpUserEntity();
		mpUserEntity.setOpenId(openId);
		return baseDao.selectByEntity(mpUserEntity).stream().findFirst().orElse(null);
	}

	@Override
	public MpUserEntity findByJiacn(String jiacn) {
		MpUserEntity mpUserEntity = new MpUserEntity();
		mpUserEntity.setJiacn(jiacn);
		return baseDao.selectByEntity(mpUserEntity).stream().findFirst().orElse(null);
	}

	@Override
	@Async
	public void sync(List<MpUserEntity> userList) {
		for(MpUserEntity user : userList) {
			// 保存公众号用户
			MpUserEntity mpUser = findByOpenId(user.getOpenId());
			if (mpUser == null) {
				create(user);
			} else {
				BeanUtil.copyPropertiesIgnoreEmpty(user, mpUser);
				baseDao.updateById(mpUser);
			}
		}
	}

	public int unsubscribe(MpUserEntity example) {
		return baseDao.unsubscribe(example);
	}
}
