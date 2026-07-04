package cn.jia.chat.tool;

import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;
import cn.jia.mat.entity.MatVoteEntity;
import cn.jia.mat.entity.MatVoteQuestionEntity;
import cn.jia.mat.entity.MatVoteReqVO;
import cn.jia.mat.entity.MatVoteResVO;
import cn.jia.mat.entity.MatVoteTickEntity;
import cn.jia.mat.service.MatMediaService;
import cn.jia.mat.service.MatNewsService;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MaterialTools {

    private final MatMediaService matMediaService;
    private final MatNewsService matNewsService;
    private final MatPhraseService matPhraseService;
    private final MatVoteService matVoteService;

    // ========== 媒体资源 ==========

    @Tool(name = "listMedias", description = "查询媒体资源列表（图片/视频/音频等素材）")
    public Map<String, Object> listMedias(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        MatMediaEntity query = new MatMediaEntity();
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<MatMediaEntity> pageInfo = matMediaService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getMediaDetail", description = "查看媒体资源详情")
    public MatMediaEntity getMediaDetail(
            @ToolParam(description = "媒体资源ID") Long id) {
        return matMediaService.get(id);
    }

    // ========== 图文消息 ==========

    @Tool(name = "listNews", description = "查询图文消息列表（文章、资讯等）")
    public Map<String, Object> listNews(
            @ToolParam(description = "搜索关键词，匹配标题") String keyword,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        MatNewsEntity query = new MatNewsEntity();
        if (keyword != null && !keyword.isEmpty()) {
            query.setTitle(keyword);
        }
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<MatNewsEntity> pageInfo = matNewsService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getNewsDetail", description = "查看图文消息详情")
    public MatNewsEntity getNewsDetail(
            @ToolParam(description = "图文ID") Long id) {
        return matNewsService.get(id);
    }

    // ========== 短语/语录 ==========

    @Tool(name = "listPhrases", description = "查询短语/语录列表，支持按标签过滤")
    public Map<String, Object> listPhrases(
            @ToolParam(description = "标签过滤，为空则返回全部") String tag,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认20") Integer pageSize) {
        MatPhraseEntity query = new MatPhraseEntity();
        if (tag != null && !tag.isEmpty()) {
            query.setTag(tag);
        }
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 20;
        PageInfo<MatPhraseEntity> pageInfo = matPhraseService.findPage(query, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getRandomPhrase", description = "随机获取一条短语/语录")
    public MatPhraseEntity getRandomPhrase(
            @ToolParam(description = "标签过滤，可选") String tag) {
        MatPhraseEntity query = new MatPhraseEntity();
        if (tag != null && !tag.isEmpty()) {
            query.setTag(tag);
        }
        return matPhraseService.findRandom(query);
    }

    @Tool(name = "votePhrase", description = "为短语点赞或点踩")
    public Map<String, Object> votePhrase(
            @ToolParam(description = "短语ID") Long phraseId,
            @ToolParam(description = "投票类型：1点赞 0点踩") Integer voteType) {
        MatPhraseVoteEntity vote = new MatPhraseVoteEntity();
        vote.setPhraseId(phraseId);
        vote.setVote(voteType);
        matPhraseService.vote(vote);
        return Map.of("phraseId", phraseId, "voted", true);
    }

    // ========== 投票/问卷 ==========

    @Tool(name = "listVotes", description = "查询投票活动列表")
    public Map<String, Object> listVotes(
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<MatVoteEntity> pageInfo = matVoteService.list(page, size, new MatVoteReqVO(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getVoteDetail", description = "查看投票活动详情，包括选项和统计")
    public Map<String, Object> getVoteDetail(
            @ToolParam(description = "投票ID") Long voteId) throws Exception {
        MatVoteResVO vote = matVoteService.find(voteId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vote", vote);
        return result;
    }

    @Tool(name = "getVoteQuestion", description = "获取投票中的一道题目")
    public MatVoteQuestionEntity getVoteQuestion(
            @ToolParam(description = "题目ID") Long questionId) {
        return matVoteService.findQuestion(questionId);
    }

    @Tool(name = "submitVote", description = "提交投票/问卷答题，opt为选项内容")
    public Map<String, Object> submitVote(
            @ToolParam(description = "投票题目ID") Long questionId,
            @ToolParam(description = "选项内容，如 A/B/C 或具体选项文本") String opt) {
        MatVoteTickEntity tick = new MatVoteTickEntity();
        tick.setQuestionId(questionId);
        tick.setOpt(opt);
        boolean success = matVoteService.tick(tick);
        return Map.of("questionId", questionId, "submitted", success);
    }

    @Tool(name = "listUserVoteRecords", description = "查看用户的投票记录")
    public List<MatVoteTickEntity> listUserVoteRecords(
            @ToolParam(description = "投票ID") Long voteId) {
        MatVoteTickEntity query = new MatVoteTickEntity();
        query.setVoteId(voteId);
        return matVoteService.findTickByJiacn(query);
    }
}
