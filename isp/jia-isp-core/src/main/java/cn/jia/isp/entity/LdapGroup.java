package cn.jia.isp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.ldap.odm.annotations.Attribute;
import org.springframework.ldap.odm.annotations.Entry;
import org.springframework.ldap.odm.annotations.Id;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;
import java.util.Set;

@Entry(objectClasses = { "top", "posixGroup" }, base = "ou=Groups")
@Data
public class LdapGroup {

	@Id
	@JsonIgnore
	private Name dn;

	@Attribute(name = "cn")
	private String cn;

	@Attribute(name = "description")
	private String description;

	@Attribute(name = "gidNumber")
	private Integer gidNumber;

	@Attribute(name = "memberUid")
	private Set<String> memberUid;

	public LdapGroup(String cn) {
		this.dn = LdapNameBuilder.newInstance().add("ou=Groups").add("cn", cn).build();
	}

	public LdapGroup() {}
	
	public void setCn(String cn) {
		this.cn = cn;
		this.dn = LdapNameBuilder.newInstance().add("ou=Groups").add("cn", cn).build();
	}
}