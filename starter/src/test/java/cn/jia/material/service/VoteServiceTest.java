package cn.jia.material.service;

import cn.jia.core.util.StreamUtil;
import cn.jia.mat.service.MatVoteService;
import cn.jia.test.BaseDbUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

public class VoteServiceTest extends BaseDbUnitTest {

    @Autowired
    private MatVoteService matVoteService;
    @Value("classpath:testObject/material/question.txt")
    private Resource questionResource;
    @Value("classpath:testObject/material/answer.txt")
    private Resource answerResource;

    @Test
    void batchImport() throws Exception {
        String question = StreamUtil.readText(questionResource.getInputStream());
        String answer = StreamUtil.readText(answerResource.getInputStream());
        matVoteService.batchImport("题目", question, answer);
    }
}