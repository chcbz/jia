package cn.jia.task.entity;

public class TaskPlanExample  extends TaskPlan {

    private Long startTimeStart;
    
    private Long startTimeEnd;

    private Long endTimeStart;
    
    private Long endTimeEnd;
    
    private Integer historyFlag;

	public Long getStartTimeStart() {
		return startTimeStart;
	}

	public void setStartTimeStart(Long startTimeStart) {
		this.startTimeStart = startTimeStart;
	}

	public Long getStartTimeEnd() {
		return startTimeEnd;
	}

	public void setStartTimeEnd(Long startTimeEnd) {
		this.startTimeEnd = startTimeEnd;
	}

	public Long getEndTimeStart() {
		return endTimeStart;
	}

	public void setEndTimeStart(Long endTimeStart) {
		this.endTimeStart = endTimeStart;
	}

	public Long getEndTimeEnd() {
		return endTimeEnd;
	}

	public void setEndTimeEnd(Long endTimeEnd) {
		this.endTimeEnd = endTimeEnd;
	}

	public Integer getHistoryFlag() {
		return historyFlag;
	}

	public void setHistoryFlag(Integer historyFlag) {
		this.historyFlag = historyFlag;
	}

}