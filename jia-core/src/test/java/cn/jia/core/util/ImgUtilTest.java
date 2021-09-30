package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.net.URL;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ImgUtilTest {

    @Test
    void getImageStr() throws Exception {
        byte[] img = ImgUtil.fromURL(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132"));
//        String imgstr = StreamUtil.readText(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132"));
//        byte[] img = imgstr.getBytes();
        int[] data = new int[img.length];
        for(int i=0;i<img.length;i++) {
            data[i] = img[i] & 0xff;
        }
        String hexStr = StringUtils.byteToHex(img);
        String b = StringUtils.fromHexString(hexStr);
        FileOutputStream out = new FileOutputStream("D:/tmp/test.jpeg");
        out.write(Objects.requireNonNull(img));
        out.flush();
        out.close();
        System.out.println(ImgUtil.GetImageStr(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132")));
    }
}