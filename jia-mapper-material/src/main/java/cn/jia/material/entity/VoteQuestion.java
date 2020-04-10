package cn.jia.material.entity;

import java.util.List;

public class VoteQuestion {
    private Integer id;

    private Integer voteId;

    private String title;

    private Integer multi;

    private Integer point;

    private String opt;

    private List<VoteItem> items;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getVoteId() {
        return voteId;
    }

    public void setVoteId(Integer voteId) {
        this.voteId = voteId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? null : title.trim();
    }

    public Integer getMulti() {
        return multi;
    }

    public void setMulti(Integer multi) {
        this.multi = multi;
    }

    public Integer getPoint() {
        return point;
    }

    public void setPoint(Integer point) {
        this.point = point;
    }

    public String getOpt() {
        return opt;
    }

    public void setOpt(String opt) {
        this.opt = opt == null ? null : opt.trim();
    }

    public List<VoteItem> getItems() {
        return items;
    }

    public void setItems(List<VoteItem> items) {
        this.items = items;
    }
}