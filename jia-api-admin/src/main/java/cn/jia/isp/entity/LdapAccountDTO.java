package cn.jia.isp.entity;

import lombok.Data;

@Data
public class LdapAccountDTO {

	private Integer serverId;

	private String cn;

	private String uid;
	
	private Integer shadowMax;
	
	private Integer shadowWarning;
	
	private String loginShell;
	
	private Integer uidNumber;
	
	private Integer gidNumber;
	
	private String homeDirectory;
	
	private String sambaSID;

	private String sambaNTPassword;
	
	private String sambaAcctFlags;
	
	private String sambaPwdLastSet;

	private String userPassword;

	private String radiusUserPassword;

}