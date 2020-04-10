package cn.jia.oauth.service;

import cn.jia.oauth.entity.Client;

public interface ClientService {
	
	Client find(String id);

	void delete(String id);
	
	Client findByAppcn(String appcn);
	
	void update(Client client);
	
	void create(Client client);

	void addResource(String resourceId, String clientId);
}
