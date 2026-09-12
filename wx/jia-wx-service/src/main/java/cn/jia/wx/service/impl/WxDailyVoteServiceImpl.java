package cn.jia.wx.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.mat.entity.MatDailyVoteAnswerResult;
import cn.jia.mat.service.MatVoteService;
import cn.jia.point.common.PointConstants;
import cn.jia.point.service.PointService;
import cn.jia.wx.dao.WxDailyVoteReceiptDao;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerCommand;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.dailyvote.WxDailyVoteKeys;
import cn.jia.wx.dailyvote.WxDailyVoteReplayQuery;
import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;
import cn.jia.wx.service.WxDailyVoteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WxDailyVoteServiceImpl implements WxDailyVoteService {

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final WxDailyVoteReceiptDao receiptDao;
    private final MatVoteService voteService;
    private final PointService pointService;

    public WxDailyVoteServiceImpl(WxDailyVoteReceiptDao receiptDao,
                                  MatVoteService voteService,
                                  PointService pointService) {
        this.receiptDao = receiptDao;
        this.voteService = voteService;
        this.pointService = pointService;
    }

    @Override
    public Optional<WxDailyVoteAnswerResult> findReplay(WxDailyVoteReplayQuery query) {
        validateIdentity(query.appid(), query.messageKey(), query.userKey());
        WxDailyVoteReceiptEntity receipt = receiptDao.selectCompletedByMessage(query.appid(), query.messageKey());
        if (receipt == null) {
            return Optional.empty();
        }
        validateReceiptIdentity(receipt, query.appid(), query.messageKey(), query.userKey(), receipt.getQuestionId());
        String fingerprint = WxDailyVoteKeys.requestFingerprint(
                query.appid(), query.userKey(), receipt.getQuestionId(), query.answer());
        if (!Objects.equals(fingerprint, receipt.getRequestFingerprint())) {
            throw new IllegalStateException("daily vote replay payload mismatch");
        }
        return Optional.of(toResult(receipt, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WxDailyVoteAnswerResult answer(WxDailyVoteAnswerCommand command) {
        validateIdentity(command.appid(), command.messageKey(), command.userKey());
        if (command.jiacn() == null || command.jiacn().isBlank() || command.questionId() <= 0
                || command.answer() == null) {
            throw new IllegalArgumentException("daily vote command is incomplete");
        }

        WxDailyVoteMessageReceiptEntity durableMessage = receiptDao.selectMessage(
                command.appid(), command.messageKey());
        if (durableMessage != null) {
            return replayBoundMessage(command, durableMessage);
        }

        String processorToken = UUID.randomUUID().toString();
        String requestFingerprint = requestFingerprint(command, command.questionId());
        long now = DateUtil.nowTime();
        WxDailyVoteReceiptEntity claim = new WxDailyVoteReceiptEntity()
                .setAppid(command.appid())
                .setMessageKey(command.messageKey())
                .setUserKey(command.userKey())
                .setRequestFingerprint(requestFingerprint)
                .setQuestionId(command.questionId())
                .setProcessorToken(processorToken)
                .setStatus(PROCESSING);
        claim.setCreateTime(now);
        claim.setUpdateTime(now);
        receiptDao.claim(claim);

        List<WxDailyVoteReceiptEntity> conflicts = receiptDao.lockConflicts(
                command.appid(), command.messageKey(), command.userKey(), command.questionId());
        if (conflicts.size() != 1) {
            throw new IllegalStateException("daily vote receipt identity conflict");
        }
        WxDailyVoteReceiptEntity receipt = conflicts.getFirst();
        boolean sameMessage = Objects.equals(command.messageKey(), receipt.getMessageKey());
        long boundQuestionId;
        String boundFingerprint;
        if (sameMessage) {
            validateReceiptIdentity(receipt, command.appid(), command.messageKey(),
                    command.userKey(), receipt.getQuestionId());
            boundQuestionId = receipt.getQuestionId();
            boundFingerprint = requestFingerprint(command, boundQuestionId);
            if (!Objects.equals(boundFingerprint, receipt.getRequestFingerprint())) {
                throw new IllegalStateException("daily vote replay payload mismatch");
            }
        } else {
            validateReceiptIdentity(receipt, command.appid(), null,
                    command.userKey(), command.questionId());
            boundQuestionId = command.questionId();
            boundFingerprint = requestFingerprint;
        }
        claimAndValidateMessage(receipt.getId(), command.appid(), command.messageKey(),
                command.userKey(), boundQuestionId, boundFingerprint, now);

        if (!Objects.equals(processorToken, receipt.getProcessorToken())) {
            if (!COMPLETED.equals(receipt.getStatus())) {
                throw new IllegalStateException("daily vote receipt is not replayable");
            }
            return toResult(receipt, false);
        }
        if (!PROCESSING.equals(receipt.getStatus()) || !sameMessage
                || !Objects.equals(command.questionId(), receipt.getQuestionId())) {
            throw new IllegalStateException("daily vote receipt claim is invalid");
        }

        MatDailyVoteAnswerResult answer = voteService.answerDaily(
                receipt.getQuestionId(), command.jiacn(), command.answer());
        int pointAwarded = 0;
        if (answer.correct()) {
            pointService.add(command.jiacn(), answer.point(), PointConstants.POINT_TYPE_VOTE);
            pointAwarded = answer.point();
        }
        String reply = answer.correct()
                ? "恭喜你，答案正确，增加" + answer.point() + "积分！"
                : "很遗憾，回答错误，正确答案是" + answer.correctOption() + ",下次继续努力！";
        if (receiptDao.complete(receipt.getId(), processorToken, boundFingerprint, answer.correct(),
                pointAwarded, reply, DateUtil.nowTime()) != 1) {
            throw new IllegalStateException("daily vote receipt completion failed");
        }
        return new WxDailyVoteAnswerResult(
                receipt.getId(), receipt.getQuestionId(), reply, answer.correct(), pointAwarded, true);
    }

    private WxDailyVoteAnswerResult replayBoundMessage(WxDailyVoteAnswerCommand command,
                                                       WxDailyVoteMessageReceiptEntity messageReceipt) {
        if (messageReceipt.getQuestionId() == null || messageReceipt.getQuestionId() <= 0) {
            throw new IllegalStateException("daily vote message receipt identity conflict");
        }
        String fingerprint = requestFingerprint(command, messageReceipt.getQuestionId());
        validateMessageReceipt(messageReceipt, messageReceipt.getReceiptId(), command.appid(),
                command.messageKey(), command.userKey(), messageReceipt.getQuestionId(), fingerprint);

        WxDailyVoteReceiptEntity receipt = receiptDao.lockById(messageReceipt.getReceiptId());
        validateReceiptIdentity(receipt, command.appid(), null, command.userKey(),
                messageReceipt.getQuestionId());
        if (!Objects.equals(messageReceipt.getReceiptId(), receipt.getId())
                || !isHash(receipt.getMessageKey()) || !isHash(receipt.getRequestFingerprint())
                || (Objects.equals(command.messageKey(), receipt.getMessageKey())
                    && !Objects.equals(fingerprint, receipt.getRequestFingerprint()))) {
            throw new IllegalStateException("daily vote receipt identity mismatch");
        }
        return toResult(receipt, false);
    }

    private void claimAndValidateMessage(long receiptId, String appid, String messageKey,
                                         String userKey, long questionId,
                                         String requestFingerprint, long now) {
        WxDailyVoteMessageReceiptEntity claim = new WxDailyVoteMessageReceiptEntity()
                .setReceiptId(receiptId)
                .setAppid(appid)
                .setMessageKey(messageKey)
                .setUserKey(userKey)
                .setRequestFingerprint(requestFingerprint)
                .setQuestionId(questionId);
        claim.setCreateTime(now);
        claim.setUpdateTime(now);
        receiptDao.claimMessage(claim);

        WxDailyVoteMessageReceiptEntity messageReceipt = receiptDao.lockMessage(appid, messageKey);
        validateMessageReceipt(messageReceipt, receiptId, appid, messageKey,
                userKey, questionId, requestFingerprint);
    }

    private void validateMessageReceipt(WxDailyVoteMessageReceiptEntity messageReceipt, Long receiptId,
                                        String appid, String messageKey, String userKey,
                                        Long questionId, String requestFingerprint) {
        if (messageReceipt == null || messageReceipt.getId() == null || receiptId == null
                || messageReceipt.getReceiptId() == null || questionId == null
                || !Objects.equals(receiptId, messageReceipt.getReceiptId())
                || !Objects.equals(appid, messageReceipt.getAppid())
                || !Objects.equals(messageKey, messageReceipt.getMessageKey())
                || !Objects.equals(userKey, messageReceipt.getUserKey())
                || !Objects.equals(requestFingerprint, messageReceipt.getRequestFingerprint())
                || !Objects.equals(questionId, messageReceipt.getQuestionId())) {
            throw new IllegalStateException("daily vote message receipt identity conflict");
        }
    }

    private String requestFingerprint(WxDailyVoteAnswerCommand command, long questionId) {
        return WxDailyVoteKeys.requestFingerprint(
                command.appid(), command.userKey(), questionId, command.answer());
    }

    private void validateIdentity(String appid, String messageKey, String userKey) {
        if (appid == null || appid.isBlank() || !isHash(messageKey) || !isHash(userKey)) {
            throw new IllegalArgumentException("daily vote identity is invalid");
        }
    }

    private void validateReceiptIdentity(WxDailyVoteReceiptEntity receipt, String appid,
                                         String messageKey, String userKey, Long questionId) {
        if (receipt == null || receipt.getId() == null || receipt.getQuestionId() == null
                || !Objects.equals(appid, receipt.getAppid())
                || (messageKey != null && !Objects.equals(messageKey, receipt.getMessageKey()))
                || !Objects.equals(userKey, receipt.getUserKey())
                || !Objects.equals(questionId, receipt.getQuestionId())) {
            throw new IllegalStateException("daily vote receipt identity mismatch");
        }
    }

    private WxDailyVoteAnswerResult toResult(WxDailyVoteReceiptEntity receipt, boolean firstProcessing) {
        if (!COMPLETED.equals(receipt.getStatus()) || receipt.getCorrect() == null
                || receipt.getPointAwarded() == null || receipt.getReplyContent() == null) {
            throw new IllegalStateException("daily vote receipt result is incomplete");
        }
        return new WxDailyVoteAnswerResult(receipt.getId(), receipt.getQuestionId(),
                receipt.getReplyContent(), receipt.getCorrect() == 1,
                receipt.getPointAwarded(), firstProcessing);
    }

    private boolean isHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
}
