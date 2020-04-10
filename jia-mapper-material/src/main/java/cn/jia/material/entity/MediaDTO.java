package cn.jia.material.entity;

public class MediaDTO extends Media {

    private String link; //链接地址

    private String content; //内容

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}