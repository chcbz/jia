package cn.jia.user.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class ClientRegister extends User{

	private String orgCode;
	
	private String orgName;
	
}
