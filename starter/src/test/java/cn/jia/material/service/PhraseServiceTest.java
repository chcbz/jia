package cn.jia.material.service;

import cn.jia.core.util.DateUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.StreamUtil;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.test.BaseDbUnitTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.FileInputStream;
import java.util.Date;

public class PhraseServiceTest extends BaseDbUnitTest {

    @Autowired
    private MatPhraseService matPhraseService;

    @Test
    @Disabled
    void create() throws Exception {
        File file = new File("C:\\Users\\Think\\Desktop\\毒鸡汤.txt");
        FileInputStream is = new FileInputStream(file);
        String txt = StreamUtil.readText(is);
        txt = txt.replaceAll("([ABCDEFGH]|\\d+)[.．]", "$1、")
                .replaceAll("[ 　]+\r\n", "\r\n")
                .replaceAll("、[ 　]+", "、")
                .replaceAll("\r\n\r\n", "\r\n");
        long now = DateUtil.genTime(new Date());

        int i = 0;
        while (txt.contains((++i) + "、")) {
            String seq = i + "、";
            txt = txt.substring(txt.indexOf(seq));
            MatPhraseEntity phrase = new MatPhraseEntity();
            phrase.setClientId("jia_client");
            phrase.setCreateTime(now);
            phrase.setUpdateTime(now);
            phrase.setTag("毒鸡汤");
            phrase.setContent(txt.substring(seq.length(), !txt.contains("\n") ? txt.length() : txt.indexOf("\n")));
            txt = txt.substring(!txt.contains("\n") ? 0 : txt.indexOf("\n") + 1);
            matPhraseService.create(phrase);
        }
    }

    @Test
    @Disabled
    void th() throws Exception {
        String baseUrl = "https://8zt.cc";
        String link = "/soup/a1dd6.html";
        long now = DateUtil.genTime(new Date());
        while (true) {
            String html = HttpUtil.sendGet(baseUrl + link);
            String key = "<span id=\"sentence\" style=\"font-size: 2rem;\">";
            html = html.substring(html.indexOf(key) + key.length());
            String content = html.substring(0, html.indexOf("</span>")).trim();
            String nextLinkKey = "<a class=\"btn btn-success btn-filled btn-xs\" href=\"";
            html = html.substring(html.indexOf(nextLinkKey) + nextLinkKey.length());
            link = html.substring(0, html.indexOf("\""));
            System.out.println(content + " " + link);

            MatPhraseEntity phrase = new MatPhraseEntity();
            phrase.setClientId("jia_client");
            phrase.setCreateTime(now);
            phrase.setUpdateTime(now);
            phrase.setTag("毒鸡汤");
            phrase.setContent(content);
            matPhraseService.create(phrase);

            Thread.sleep(200);
        }
    }
}