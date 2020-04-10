package cn.jia.material.entity;

public class NewsDTO extends News {

    private String piclink; //链接地址

    private String bodylink;

    private String content; //内容

    public String getPiclink() {
        return piclink;
    }

    public void setPiclink(String piclink) {
        this.piclink = piclink;
    }

    public String getBodylink() {
        return bodylink;
    }

    public void setBodylink(String bodylink) {
        this.bodylink = bodylink;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}