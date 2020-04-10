package cn.jia.oauth.entity;

import org.springframework.security.oauth2.provider.client.BaseClientDetails;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class Client extends BaseClientDetails{
	private static final long serialVersionUID = -4154033943401815782L;

	private String appcn;
}
