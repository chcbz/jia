package cn.jia.material.entity;

import java.util.List;

public class Vote {
    private Integer id;

    private String clientId;

    private String name;

    private Long startTime;

    private Long closeTime;

    private Integer num;
    
    private List<VoteQuestion> questions;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(Long closeTime) {
        this.closeTime = closeTime;
    }

    public Integer getNum() {
        return num;
    }

    public void setNum(Integer num) {
        this.num = num;
    }

	public List<VoteQuestion> getQuestions() {
		return questions;
	}

	public void setQuestions(List<VoteQuestion> questions) {
		this.questions = questions;
	}
}