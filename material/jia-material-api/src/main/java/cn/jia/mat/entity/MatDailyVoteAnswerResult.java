package cn.jia.mat.entity;

/**
 * Question snapshot and persisted judgement produced in one transaction.
 */
public record MatDailyVoteAnswerResult(
        long questionId,
        boolean correct,
        int point,
        String correctOption) {
}
