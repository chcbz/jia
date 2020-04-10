package cn.jia.material.entity;

public class PhraseVote {
    private Integer id;

    private String jiacn;

    private Integer phraseId;

    private Integer vote;

    private Long time;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getJiacn() {
        return jiacn;
    }

    public void setJiacn(String jiacn) {
        this.jiacn = jiacn == null ? null : jiacn.trim();
    }

    public Integer getPhraseId() {
        return phraseId;
    }

    public void setPhraseId(Integer phraseId) {
        this.phraseId = phraseId;
    }

    public Integer getVote() {
        return vote;
    }

    public void setVote(Integer vote) {
        this.vote = vote;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }
}