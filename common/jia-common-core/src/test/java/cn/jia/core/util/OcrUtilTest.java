package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class OcrUtilTest {
    @Test
    void recognizeText() throws Exception {
        String path = this.getClass().getResource("/ocr").getPath();
        String imgPath = path + "/ocr_date.png";
        String imgValue = OcrUtil.recognizeText(new File(imgPath), "png");
        assertEquals(imgValue, "1955-03-13");

//        String origImgPath = "D:\\workspace\\chaoyoufan\\202202\\IMG_3275.PNG";
        imgPath = path + "/ocr_name.png";
//        ImgUtil.cutPic(origImgPath, imgPath, 210, 220, 190, 70);
//        OpenCvUtil.threshold(imgPath, imgPath);
//        ImgUtil.compressPic(imgPath, imgPath, 1200, 500, true);
//        // 这里对图片黑白处理,增强识别率.这里先通过截图,截取图片中需要识别的部分
//        textImage = ImgUtil.transformGray24BitMap(textImage);
//        // 图片锐化
//        textImage = ImageHelper.convertImageToBinary(textImage);
//        // 图片放大倍数,增强识别率(很多图片本身无法识别,放大5倍时就可以轻易识,但是考滤到客户电脑配置低,针式打印机打印不连贯的问题,这里就放大5倍)
//        textImage = ImageHelper.getScaledInstance(textImage, textImage.getWidth() * 1, textImage.getHeight() * 1);
//
//        textImage = ImageHelper.convertImageToBinary(textImage);
        imgValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(imgPath), "png"));
        assertEquals(imgValue, "蔡明");
    }

    @Test
    @Disabled
    void recognizeCustomerInfo() throws Exception {
        String path = this.getClass().getResource("/ocr").getPath();
        String rootPath = "D:\\workspace\\chaoyoufan\\202202";
        File pathFile = new File(rootPath);
        File[] files = pathFile.listFiles();
        for (File file : files != null ? files : new File[0]) {
            String imgPath = rootPath + "/" + file.getName();
            String outputPath = "/tmp/" + FileUtil.getName(file.getName());
            //noinspection ResultOfMethodCallIgnored
            new File(outputPath).mkdirs();
//            String imgPath = path + "/opencv_test.png";
//        log.info("imgPath: " + imgPath);

            String namePath = outputPath + "/ocr_name.png";
            ImgUtil.cutPic(imgPath, namePath, 160, 220, 180, 70);
            String nameValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(namePath), "png"));
            if (StringUtil.isBlank(nameValue)) {
                ImgUtil.cutPic(imgPath, namePath, 170, 230, 80, 60);
                nameValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(namePath), "png"));
            }
//        log.info("name: " + nameValue);

            String sexPath = outputPath + "/ocr_sex.png";
            ImgUtil.cutPic(imgPath, sexPath, 170, 230, 200, 60);
            boolean manIncludeFlag = OpenCvUtil.includeImage(new File(sexPath), new File(path + "/ocr_test_m.png"));
            String sexValue = (manIncludeFlag ? "M" : "F");
//        log.info("sex: " + (sexValue ? "M" : "F"));

            String birthPath = outputPath + "/ocr_birth.png";
            ImgUtil.cutPic(imgPath, birthPath, 170, 360, 150, 50);
            String birthValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(birthPath), "png"));
//        log.info("birth: " + birthValue);

            String phonePath = outputPath + "/ocr_phone.png";
            ImgUtil.cutPic(imgPath, phonePath, 170, 820, 350, 70);
            String phoneValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(phonePath), "png"));
//        log.info("phone: " + phoneValue);

            String emailPath = outputPath + "/ocr_email.png";
            ImgUtil.cutPic(imgPath, emailPath, 170, 920, 450, 70);
            String emailValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(emailPath), "png"));
//        log.info("email: " + emailValue);

            String addressPath = outputPath + "/ocr_address.png";
            ImgUtil.cutPic(imgPath, addressPath, 170, 1000, 450, 100);
            String addressValue = StringUtil.removeBlank(OcrUtil.recognizeText(new File(addressPath), "png"));
//        log.info("address: " + addressValue);
            log.info(file.getName() + "\t" + nameValue + "\t" + sexValue + "\t" + birthValue + "\t" + phoneValue + "\t" + emailValue + "\t" + addressValue);
        }
    }

    @Test
    void createImage() {
    }
}