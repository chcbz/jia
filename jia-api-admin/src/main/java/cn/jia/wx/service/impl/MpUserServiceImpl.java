package cn.jia.wx.service.impl;

import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.ImgUtil;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.LdapUserService;
import cn.jia.wx.dao.MpUserMapper;
import cn.jia.wx.entity.MpUser;
import cn.jia.wx.service.MpUserService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Date;
import java.util.List;

@Service
public class MpUserServiceImpl implements MpUserService {
	
	@Autowired
	private MpUserMapper mpUserMapper;
	@Autowired
	private LdapUserService ldapUserService;

	@Override
	public MpUser create(MpUser mpUser) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		mpUser.setCreateTime(now);
		mpUser.setUpdateTime(now);
		mpUserMapper.insertSelective(mpUser);
		return mpUser;
	}

	@Override
	public MpUser find(Integer id) {
		return mpUserMapper.selectByPrimaryKey(id);
	}

	@Override
	public MpUser findByOpenId(String openId) {
		return mpUserMapper.selectByOpenId(openId);
	}

	@Override
	public Page<MpUser> list(MpUser example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return mpUserMapper.selectByExample(example);
	}

	@Override
	public MpUser update(MpUser mpUser) {
		//保存公众号信息
		Long now = DateUtil.genTime(new Date());
		mpUser.setUpdateTime(now);
		mpUserMapper.updateByPrimaryKeySelective(mpUser);
		return mpUser;
	}

	@Override
	public void delete(Integer id) {
		mpUserMapper.deleteByPrimaryKey(id);
	}

	@Override
	public void sync(List<MpUser> userList) throws Exception {
		for(MpUser user : userList) {
			//查找LDAP服务器是否有该用户
			LdapUser ldapUser = null;
			LdapUser params = new LdapUser();
			params.setEmail(user.getEmail());
			params.setOpenid(user.getOpenId());
			List<LdapUser> ldapUserResult = ldapUserService.search(params);
			if(ldapUserResult.size() > 0) {
				ldapUser = ldapUserResult.get(0);
				BeanUtil.copyPropertiesIgnoreEmpty(user, ldapUser);
				ldapUser.setHeadimg(ImgUtil.fromURL(new URL(user.getHeadImgUrl())));
				ldapUserService.modifyLdapUser(ldapUser);
				user.setJiacn(String.valueOf(ldapUser.getUid()));
			}
			//将用户添加到ldap服务器
			if(ldapUser == null) {
				ldapUser = new LdapUser();
				BeanUtil.copyPropertiesIgnoreEmpty(user, ldapUser);
				ldapUser.setUid(user.getOpenId());
				ldapUser.setOpenid(user.getOpenId());
				ldapUser.setCn(user.getOpenId());
				ldapUser.setSn(user.getOpenId());
				ldapUser.setHeadimg(ImgUtil.fromURL(new URL(user.getHeadImgUrl())));
				ldapUserService.create(ldapUser);
				user.setJiacn(user.getOpenId());
			}

			long now = DateUtil.genTime(new Date());
			//查找本地数据库是否有该用户，如果没有则新增，如果有则更新
			MpUser curUser = findByOpenId(user.getOpenId());
			if(curUser == null) {
				user.setCreateTime(now);
				user.setUpdateTime(now);
				//新增用户
				mpUserMapper.insertSelective(user);
			}else {
				BeanUtil.copyPropertiesIgnoreNull(user, curUser);
				curUser.setUpdateTime(now);
				mpUserMapper.updateByPrimaryKeySelective(curUser);
			}
		}
	}

}
