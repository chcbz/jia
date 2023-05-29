package cn.jia.core.util;


import com.sun.media.imageio.plugins.tiff.TIFFImageWriteParam;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 调用本地tesseract-OCR识别图片信息
 * 需要先在操作系统中安装tesseract-OCR https://github.com/tesseract-ocr/tesseract
 * 接着下载简体中文语言包 https://github.com/tesseract-ocr/tessdata
 * 设置系统环境变量TESS_HOME={tesseract-OCR安装目录}，并添加到系统Path变量中
 * 设置环境变量TESSDATA_PREFIX=$TESS_HOME\tessdata
 *
 * @author evang
 */
public class OcrUtil {
    /**
     * 英文字母小写l，并非数字1
     */
    private final static String LANG_OPTION = "-l";
    private final static String EOL = System.getProperty("line.separator");

    /**
     * 提取图片中得文字
     *
     * @param imageFile 图片文件
     * @param imageFormat 图片格式
     * @return 图片中的文字
     * @throws Exception 异常
     */
    public static String recognizeText(File imageFile, String imageFormat) throws Exception {
        File tempImage = createImage(imageFile, imageFormat);
        File outputFile = new File(imageFile.getParentFile(), "output");
        StringBuilder strB = new StringBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add("tesseract");
        cmd.add("");
        cmd.add(outputFile.getName());
        cmd.add(LANG_OPTION);
    	cmd.add("chi_sim");

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(imageFile.getParentFile());

        cmd.set(1, tempImage.getName());
        pb.command(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // tesseract.exe 1.jpg 1 -l chi_sim
        int w = process.waitFor();

        // 删除临时正在工作文件
        //noinspection ResultOfMethodCallIgnored
        tempImage.delete();

        if (w == 0) {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(outputFile.getAbsolutePath() + ".txt"),
                    StandardCharsets.UTF_8));

            String str;
            while ((str = in.readLine()) != null) {
                if (strB.length() != 0) {
                    strB.append(EOL);
                }
                strB.append(str);
            }
            in.close();
        } else {
            String msg;
            switch (w) {
                case 1:
                    msg = "Errors accessing files.There may be spaces in your image's filename.";
                    break;
                case 29:
                    msg = "Cannot recongnize the image or its selected region.";
                    break;
                case 31:
                    msg = "Unsupported image format.";
                    break;
                default:
                    msg = "Errors occurred.";
            }
            //noinspection ResultOfMethodCallIgnored
            tempImage.delete();
            throw new RuntimeException(msg);
        }
        //noinspection ResultOfMethodCallIgnored
        new File(outputFile.getAbsolutePath() + ".txt").delete();
        return strB.toString();
    }

    /**
     * 图片文件转换为tif格式
     *
     * @param imageFile   文件路径
     * @param imageFormat 文件扩展名
     * @return tif格式文件
     */
    public static File createImage(File imageFile, String imageFormat) {
        File tempFile = null;
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(imageFormat);
            ImageReader reader = readers.next();

            ImageInputStream iis = ImageIO.createImageInputStream(imageFile);
            reader.setInput(iis);
            //Read the stream metadata  
            IIOMetadata streamMetadata = reader.getStreamMetadata();

            //Set up the writeParam  
            TIFFImageWriteParam tiffWriteParam = new TIFFImageWriteParam(Locale.CHINESE);
            tiffWriteParam.setCompressionMode(ImageWriteParam.MODE_DISABLED);

            //Get tif writer and set output to file  
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
            ImageWriter writer = writers.next();

            BufferedImage bi = reader.read(0);
            IIOImage image = new IIOImage(bi, null, reader.getImageMetadata(0));
            tempFile = tempImageFile(imageFile);
            ImageOutputStream ios = ImageIO.createImageOutputStream(tempFile);
            writer.setOutput(ios);
            writer.write(streamMetadata, image, tiffWriteParam);
            ios.close();

            writer.dispose();
            reader.dispose();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return tempFile;
    }

    private static File tempImageFile(File imageFile) {
        String path = imageFile.getPath();
        StringBuilder strB = new StringBuilder(path);
        strB.insert(path.lastIndexOf('.'), 0);
        return new File(strB.toString().replaceFirst("(?<=//.)(//w+)$", "tif"));
    }
}