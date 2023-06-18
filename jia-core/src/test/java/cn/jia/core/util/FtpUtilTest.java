package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class FtpUtilTest {

    @Test
    void uploadFile() {
        /* FileInputStream in=new FileInputStream(new File("/Users/Downloads/test.png"));
	        boolean flag = uploadFile("111.231.31.41", 21, "ftp_test", "test0", "/ftp_data/images","", "holle.jpg", in);*/
        boolean flag = FtpUtil.downloadFile("111.231.31.41", 21,  "ftp_test", "testO","/ftp_data/images","test.jpg","/Volumes/H");
        log.info(String.valueOf(flag));
    }



    /**
     * 测试
     * @param args
     */
    public static void main(String[] args) {
        try {

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}