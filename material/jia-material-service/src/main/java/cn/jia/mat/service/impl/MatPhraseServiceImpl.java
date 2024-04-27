package cn.jia.mat.service.impl;

import cn.jia.core.common.EsHandler;
import cn.jia.core.elasticsearch.ElasticsearchService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

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
    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    @Value("${phrase.elasticsearch.index:phrase}")
    private String indexName = "phrase";

    @Override
    public MatPhraseEntity create(MatPhraseEntity phrase) {
        //判断是否有相似的短语
        SearchHits<?> hits = elasticsearchService.searchMatch(
                indexName, "content", phrase.getContent(), MatPhraseEntity.class);
        if (hits == null) {
            throw new EsRuntimeException(MatErrorConstants.ES_SERVICE_ERROR);
        }
        if (hits.getMaxScore() > 40) {
            throw new EsRuntimeException(MatErrorConstants.PHRASE_HAS_EXIST, hits.getSearchHit(0).getContent());
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
            if (StringUtils.isNotEmpty(phrase.getJiacn()) && upPhrase.getUp() % 10 == 0) {
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
