package cn.jia.isp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.ldap.odm.annotations.Attribute;
import org.springframework.ldap.odm.annotations.Entry;
import org.springframework.ldap.odm.annotations.Id;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;

@Entry(objectClasses = { "account", "posixAccount", "top", "shadowAccount", "sambaSamAccount", "radiusAccount" }, base = "ou=Users")
@Data
public class LdapAccount {

	@Id
	@JsonIgnore
	private Name dn;

	@Attribute(name = "cn")
	private String cn;

	@Attribute(name = "uid")
	private String uid;
	
	@Attribute(name = "shadowMax")
	private Integer shadowMax;
	
	@Attribute(name = "shadowWarning")
	private Integer shadowWarning;
	
	@Attribute(name = "loginShell")
	private String loginShell;
	
	@Attribute(name = "uidNumber")
	private Integer uidNumber;
	
	@Attribute(name = "gidNumber")
	private Integer gidNumber;
	
	@Attribute(name = "homeDirectory")
	private String homeDirectory;
	
	@Attribute(name = "sambaSID")
	private String sambaSID;

	@Attribute(name = "sambaNTPassword")
	private String sambaNTPassword;
	
	@Attribute(name = "sambaAcctFlags")
	private String sambaAcctFlags;
	
	@Attribute(name = "sambaPwdLastSet")
	private String sambaPwdLastSet;

	@Attribute(name = "userPassword", type = Attribute.Type.BINARY)
	private byte[] userPassword;

	@Attribute(name = "radiusUserPassword")
	private String radiusUserPassword;

	public LdapAccount(String uid) {
		this.dn = LdapNameBuilder.newInstance().add("ou", "Users").add("uid", uid).build();
		this.uid = uid;
	}

	public LdapAccount() {
	}

	public void setUid(String uid) {
		this.uid = uid;
		if (this.dn == null) {
			this.dn = LdapNameBuilder.newInstance().add("ou", "Users").add("uid", uid).build();
		}
	}
}