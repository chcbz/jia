package cn.jia.mat.service.impl;

import cn.jia.core.common.EsHandler;
import cn.jia.core.elasticsearch.ElasticsearchService;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.dao.MatPhraseDao;
import cn.jia.mat.dao.MatPhraseVoteDao;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.point.common.PointConstants;
import cn.jia.point.service.PointService;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class MatPhraseServiceImpl extends BaseServiceImpl<MatPhraseDao, MatPhraseEntity> implements MatPhraseService {

    @Autowired
    private MatPhraseDao matPhraseDao;
    @Autowired
    private MatPhraseVoteDao matPhraseVoteDao;
    @Autowired(required = false)
    private PointService pointService;
    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    @Value("${phrase.elasticsearch.index:phrase}")
    private String indexName = "phrase";

    @Override
    public MatPhraseEntity create(MatPhraseEntity phrase) {
        //判断是否有相似的短语
        SearchResponse<MatPhraseEntity> searchResponse = elasticsearchService.searchMatch(
                indexName, "content", phrase.getContent(), MatPhraseEntity.class);
        if (searchResponse == null) {
            throw new EsRuntimeException(MatErrorConstants.ES_SERVICE_ERROR);
        }
        Double maxScore = Optional.ofNullable(searchResponse.hits()).map(HitsMetadata::maxScore).orElse(0D);
        if (maxScore > 40) {
            throw new EsRuntimeException(MatErrorConstants.PHRASE_HAS_EXIST,
                    Optional.ofNullable(searchResponse.hits().hits()).map(List::getFirst).map(Hit::source).map(MatPhraseEntity::getContent));
        }

        matPhraseDao.insert(phrase);
        return phrase;
    }

    @Override
    public boolean delete(Serializable id) {
        matPhraseDao.deleteById(id);

        //删除索引
        elasticsearchService.delete(String.valueOf(id), indexName);
        return true;
    }

    @Override
    public MatPhraseEntity findRandom(MatPhraseEntity example) {
        return matPhraseDao.selectRandom(example);
    }

    @Override
    public void vote(MatPhraseVoteEntity vote) {
        MatPhraseEntity phrase = matPhraseDao.selectById(vote.getPhraseId());
        EsHandler.assertNotNull(phrase);
        MatPhraseEntity upPhrase = new MatPhraseEntity();
        upPhrase.setId(phrase.getId());
        if (vote.getVote().equals(1)) {
            upPhrase.setUp(phrase.getUp() + 1);
            //每被点赞10次，增加1积分
            if (StringUtil.isNotEmpty(phrase.getJiacn()) && upPhrase.getUp() % 10 == 0) {
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
