package cn.jia.oauth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.ldap.odm.annotations.Attribute;
import org.springframework.ldap.odm.annotations.Entry;
import org.springframework.ldap.odm.annotations.Id;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;
import java.io.Serializable;
import java.util.Set;

@Entry(objectClasses = { "top", "groupOfNames", "jiaOrg" }, base = "ou=groups,dc=jia")
@Data
public class LdapUserGroup implements Serializable {

	@Id
	@JsonIgnore
	private Name dn;

	@Attribute(name = "cn")
	private String cn;

	@Attribute(name = "clientId")
	private String clientId;

	@Attribute(name = "name")
	private String name;

	@Attribute(name = "logo", type = Attribute.Type.BINARY)
	private byte[] logo;

	@Attribute(name = "logoIcon", type = Attribute.Type.BINARY)
	private byte[] logoIcon;

	@Attribute(name = "remark")
	private String remark;

	@Attribute(name = "description")
	private String description;
	
	
	@Attribute(name = "member")
	private Set<Name> member;

	public LdapUserGroup() {}
	
	public LdapUserGroup(String cn) {
		this.cn = cn;
		this.dn = LdapNameBuilder.newInstance().add("ou=groups,dc=jia").add("cn", cn).build();
	}

	public void setCn(String cn) {
		this.cn = cn;
		this.dn = LdapNameBuilder.newInstance().add("ou=groups,dc=jia").add("cn", cn).build();
	}
}