package cn.jia.isp.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.ldap.LdapFailureClassifier;
import cn.jia.core.util.DataUtil;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.ldap.LdapFailureException;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.LdapUserService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.ContainerCriteria;

import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Named
public class LdapUserServiceImpl implements LdapUserService {

    @Inject
    private LdapTemplate ldapTemplate;

    @Override
    public LdapUser create(LdapUser user) {
        user.setUid(DataUtil.getUuid());
        ldapTemplate.create(user);
        return user;
    }

    @Override
    public LdapUser findByUid(String uid) {
        try {
            return ldapTemplate.findOne(query().base("ou=users")
                    .where("uid").is(uid), LdapUser.class);
        } catch (RuntimeException exception) {
            throw classifiedReadFailure(exception);
        }
    }

    @Override
    public List<LdapUser> findAll() {
        return ldapTemplate.findAll(LdapUser.class);
    }

    @Override
    public LdapUser modifyLdapUser(LdapUser user) {
        ldapTemplate.update(user);
        return user;
    }

    @Override
    public void deleteLdapUser(LdapUser user) {
        ldapTemplate.delete(user);
    }

    @Override
    public LdapUser findByExample(LdapUser person) {
        try {
            ContainerCriteria criteria = query().base("ou=users")
                    .where("objectClass").is("jiaPerson");
            if (person.getUid() != null) {
                criteria.and("uid").is(person.getUid());
            } else if (person.getTelephoneNumber() != null) {
                criteria.and("telephoneNumber").is(person.getTelephoneNumber());
            } else if (person.getOpenid() != null) {
                criteria.and("openid").is(person.getOpenid());
            } else if (person.getEmail() != null) {
                criteria.and("email").is(person.getEmail());
            }
            return ldapTemplate.findOne(criteria, LdapUser.class);
        } catch (RuntimeException exception) {
            throw classifiedReadFailure(exception);
        }
    }

    /**
     * 检索用户
     */
    @Override
    public List<LdapUser> search(LdapUser person) {
        try {
            ContainerCriteria criteria = query().base("ou=users")
                    .where("objectClass").is("jiaPerson");
            ContainerCriteria subCriteria = query().where("uid").is("");
            if (person.getUid() != null) {
                subCriteria.or("uid").is(person.getUid());
            }
            if (person.getTelephoneNumber() != null) {
                subCriteria.or("telephoneNumber").is(person.getTelephoneNumber());
            }
            if (person.getOpenid() != null) {
                subCriteria.or("openid").is(person.getOpenid());
            }
            if (person.getEmail() != null) {
                subCriteria.or("email").is(person.getEmail());
            }
            if (person.getWeixinid() != null) {
                subCriteria.or("weixinid").is(person.getWeixinid());
            }
            if (person.getWeiboid() != null) {
                subCriteria.or("weiboid").is(person.getWeiboid());
            }
            if (person.getGithubid() != null) {
                subCriteria.or("githubid").is(person.getGithubid());
            }
            criteria.and(subCriteria);
            return ldapTemplate.find(criteria, LdapUser.class);
        } catch (RuntimeException exception) {
            throw classifiedReadFailure(exception);
        }
    }

    private EsRuntimeException classifiedReadFailure(RuntimeException exception) {
        LdapFailureClassifier.Failure failure = LdapFailureClassifier.classify(exception);
        if (failure == LdapFailureClassifier.Failure.TIMEOUT) {
            return LdapFailureException.timeout();
        }
        if (failure == LdapFailureClassifier.Failure.UNAVAILABLE) {
            return LdapFailureException.unavailable();
        }
        return new EsRuntimeException(IspErrorConstants.USER_NOT_EXIST);
    }

}
