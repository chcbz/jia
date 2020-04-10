package cn.jia.oauth.service.impl;

import cn.jia.oauth.dao.ClientMapper;
import cn.jia.oauth.entity.Client;
import cn.jia.oauth.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class ClientServiceImpl implements ClientService {
	
	@Autowired
	private ClientMapper clientMapper;

	@Override
	public Client find(String id) {
		return clientMapper.selectByPrimaryKey(id);
	}

	@Override
	public void delete(String id) {
		clientMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Client findByAppcn(String appcn) {
		return clientMapper.selectByAppcn(appcn);
	}

	@Override
	public void update(Client client) {
		clientMapper.updateByPrimaryKeySelective(client);
	}

	@Override
	public void create(Client client) {
		clientMapper.insertSelective(client);
	}

	@Override
	public void addResource(String resourceId, String clientId){
		Set<String> resourceIds = clientMapper.selectByPrimaryKey(clientId).getResourceIds();
		if(resourceIds == null) {
			resourceIds = new HashSet<>();
		}
		Client upClient = new Client();
		upClient.setClientId(clientId);
		resourceIds.add(resourceId);
		upClient.setResourceIds(resourceIds);
		clientMapper.updateByPrimaryKeySelective(upClient);
	}
}
