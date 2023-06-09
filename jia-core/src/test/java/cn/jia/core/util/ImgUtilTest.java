package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.net.URL;
import java.util.Objects;

@Slf4j
class ImgUtilTest {

    @Test
    void getImageStr() throws Exception {
        byte[] img = ImgUtil.fromUrl(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132"));
//        String imgstr = StreamUtil.readText(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132"));
//        byte[] img = imgstr.getBytes();
        int[] data = new int[img.length];
        for(int i=0;i<img.length;i++) {
            data[i] = img[i] & 0xff;
        }
        String hexStr = StringUtils.byteToHex(img);
        String b = StringUtils.fromHexString(hexStr);
        String outputPath = this.getClass().getResource("/img").getPath();
        FileOutputStream out = new FileOutputStream(outputPath + "/test.jpeg");
        out.write(Objects.requireNonNull(img));
        out.flush();
        out.close();
        log.info(ImgUtil.getImageStr(new URL("http://thirdwx.qlogo.cn/mmopen/vi_32/PiajxSqBRaELwreASwe7C5JuocHNHSlia5XibNw5u0kdZX94X0l0gYVUApKelElBBaVNZZTQiblJnQ53wu68UvyNicg/132")));
    }

    @Test
    void compressPic() {
        String path = this.getClass().getResource("/img").getPath();
        ImgUtil.compressPic(path + "/compress_source.jpg", path + "/compress_target_default.jpg");

        ImgUtil.compressPic(path + "/compress_source.jpg", path + "/compress_target_small.jpg",
                500, 300, true);
    }

    @Test
    void cutPic() {
        String path = this.getClass().getResource("/img").getPath();
        ImgUtil.cutPic(path + "/compress_source.jpg", path + "/compress_target_cut.jpg",
                65, 90, 100, 60);
    }
}