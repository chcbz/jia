package cn.jia.oauth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.ldap.odm.annotations.Attribute;
import org.springframework.ldap.odm.annotations.Entry;
import org.springframework.ldap.odm.annotations.Id;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;
import java.io.Serializable;

@Entry(objectClasses = { "top", "person", "jiaPerson" }, base = "ou=users,dc=jia")
@Data
public class LdapUser implements Serializable {

	@Id
	@JsonIgnore
	private Name dn;

	@Attribute(name = "uid")
	private String uid;

	@Attribute(name = "uuid")
	private String uuid;

	@Attribute(name = "cn")
	private String cn;

	@Attribute(name = "sn")
	private String sn;
	
	@Attribute(name = "openid")
	private String openid;

	@Attribute(name = "weiboid")
	private String weiboid;

	@Attribute(name = "weixinid")
	private String weixinid;
	
	@Attribute(name = "telephoneNumber")
	private String telephoneNumber;
	
	@Attribute(name = "email")
	private String email;
	
	@Attribute(name = "country")
	private String country;
	
	@Attribute(name = "province")
	private String province;
	
	@Attribute(name = "city")
	private String city;

	@Attribute(name = "location")
	private String location;
	
	@Attribute(name = "sex")
	private Integer sex;
	
	@Attribute(name = "nickname")
	private String nickname;

	@Attribute(name = "headimg", type = Attribute.Type.BINARY)
	private byte[] headimg;

	@Attribute(name = "remark")
	private String remark;
	
	@Attribute(name = "userPassword", type = Attribute.Type.BINARY)
	private byte[] userPassword;
	
	/*@Attribute(name = "memberOf")
	private Set<Name> memberOf;*/
	
	public LdapUser(String uid) {
		this.uid = uid;
		this.dn = LdapNameBuilder.newInstance().add("ou=users,dc=jia").add("uid", uid).build();
	}

	public LdapUser() {}

	public void setUid(String uid) {
		this.uid = uid;
		this.dn = LdapNameBuilder.newInstance().add("ou=users,dc=jia").add("uid", uid).build();
	}
}