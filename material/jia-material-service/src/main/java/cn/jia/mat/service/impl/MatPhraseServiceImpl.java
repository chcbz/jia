package cn.jia.mat.service.impl;

import cn.jia.core.common.EsHandler;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.dao.MatPhraseDao;
import cn.jia.mat.dao.MatPhraseVoteDao;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.point.common.PointConstants;
import cn.jia.point.service.PointService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;

@Slf4j
@Service
public class MatPhraseServiceImpl extends BaseServiceImpl<MatPhraseDao, MatPhraseEntity> implements MatPhraseService {
	
	@Autowired
	private MatPhraseDao matPhraseDao;
	@Autowired
	private MatPhraseVoteDao matPhraseVoteDao;
	@Autowired(required = false)
	private PointService pointService;
	@Autowired
	private RestHighLevelClient restHighLevelClient;

	private static final String INDEX_NAME = "phrase";
	
	@Override
	public MatPhraseEntity create(MatPhraseEntity phrase) {
		//判断是否有相似的短语
		MatchQueryBuilder matchQuery = QueryBuilders.matchQuery("content", phrase.getContent());
		SearchSourceBuilder builder = SearchSourceBuilder.searchSource();
		builder.query(matchQuery);
		SearchRequest request = new SearchRequest(INDEX_NAME);
		request.source(builder);

		SearchResponse response;
		try {
			response = restHighLevelClient.search(request, RequestOptions.DEFAULT);
		} catch (IOException e) {
			throw new EsRuntimeException(MatErrorConstants.ES_SERVICE_ERROR);
		}
		SearchHits hits = response.getHits();
		if (hits.getMaxScore() > 40) {
			throw new EsRuntimeException(MatErrorConstants.PHRASE_HAS_EXIST);
		}

		Long now = DateUtil.nowTime();
		phrase.setCreateTime(now);
		phrase.setUpdateTime(now);
		phrase.setClientId(EsSecurityHandler.clientId());
		matPhraseDao.insert(phrase);
		return phrase;
	}

	@Override
	public boolean delete(Serializable id) {
		matPhraseDao.deleteById(id);

		//删除索引
		DeleteRequest request = new DeleteRequest(INDEX_NAME);
		request.id(String.valueOf(id));
		try {
			restHighLevelClient.delete(request, RequestOptions.DEFAULT);
		} catch (Exception e) {
			log.error("delete phrase index error", e);
		}
		return true;
	}

	@Override
	public MatPhraseEntity findRandom(MatPhraseEntity example) {
		return matPhraseDao.selectRandom(example);
	}

	@Override
	public void vote(MatPhraseVoteEntity vote) throws Exception {
		MatPhraseEntity phrase = matPhraseDao.selectById(vote.getPhraseId());
		EsHandler.assertNotNull(phrase);
		MatPhraseEntity upPhrase = new MatPhraseEntity();
		upPhrase.setId(phrase.getId());
		if(vote.getVote().equals(1)) {
			upPhrase.setUp(phrase.getUp() + 1);
			//每被点赞10次，增加1积分
			if(StringUtils.isNotEmpty(phrase.getJiacn()) && upPhrase.getUp() % 10 == 0) {
				pointService.add(phrase.getJiacn(), 1, PointConstants.POINT_TYPE_PHRASE);
			}
		} else {
			upPhrase.setDown(phrase.getDown() + 1);
		}
		matPhraseDao.updateById(upPhrase);
		matPhraseVoteDao.insert(vote);
	}

	@Override
	public void read(Long id) {
		MatPhraseEntity phrase = matPhraseDao.selectById(id);
		EsHandler.assertNotNull(phrase);
		MatPhraseEntity upPhrase = new MatPhraseEntity();
		upPhrase.setId(phrase.getId());
		upPhrase.setPv(phrase.getPv() + 1);
		Long now = DateUtil.nowTime();
		upPhrase.setUpdateTime(now);
		matPhraseDao.updateById(upPhrase);
	}
}
