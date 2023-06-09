package cn.jia.core.util;

import gui.ava.html.image.generator.HtmlImageGenerator;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MemoryImageSource;
import java.awt.image.PixelGrabber;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/**
 * 图片操作工具类
 * 缩略图类（通用） 本java类能将jpg、bmp、png、gif图片文件，进行等比或非等比的大小转换。 具体使用方法
 * compressPic(大图片路径,生成小图片路径,大图片文件名,生成小图片文名,生成小图片宽度,生成小图片高度,是否等比缩放(默认为true))
 *
 * @author lzh
 */
@Slf4j
public class ImgUtil {
    /**
     * 获得图片大小 传入参数 String path ：图片路径
     */
    public static long getPicSize(String path) {
        File file = new File(path);
        return file.length();
    }

    /**
     * 压缩图片
     *
     * @param inputFileName  原文件路径
     * @param outputFileName 生成文件路径
     * @return 结果，ok代表成功
     */
    public static String compressPic(String inputFileName, String outputFileName) {
        return compressPic(inputFileName, outputFileName, 100, 100, true);
    }

    /**
     * 压缩图片
     *
     * @param inputFileName  原文件路径
     * @param outputFileName 生成文件路径
     * @param width          宽度
     * @param height         高度
     * @param gp             是否等比缩放
     * @return 结果，ok代表成功
     */
    public static String compressPic(String inputFileName, String outputFileName,
                                     int width, int height, boolean gp) {
        try {
            // 获得源文件
            File file = new File(inputFileName);
            if (!file.exists()) {
                return "";
            }
            Image img = ImageIO.read(file);
            // 判断图片格式是否正确
            if (img.getWidth(null) == -1) {
                log.info(" can't read,retry!" + "<BR>");
                return "no";
            } else {
                int newWidth;
                int newHeight;
                // 判断是否是等比缩放
                if (gp) {
                    // 为等比缩放计算输出的图片宽度及高度
                    double rate1 = ((double) img.getWidth(null))
                            / (double) width + 0.1;
                    double rate2 = ((double) img.getHeight(null))
                            / (double) height + 0.1;
                    // 根据缩放比率大的进行缩放控制
                    double rate = Math.max(rate1, rate2);
                    newWidth = (int) (((double) img.getWidth(null)) / rate);
                    newHeight = (int) (((double) img.getHeight(null)) / rate);
                } else {
                    // 输出的图片宽度
                    newWidth = width;
                    // 输出的图片高度
                    newHeight = height;
                }
                BufferedImage tag = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);

                // Image.SCALE_SMOOTH 的缩略算法 生成缩略图片的平滑度的 优先级比速度高 生成的图片质量比较好 但速度慢
                tag.getGraphics().drawImage(
                        img.getScaledInstance(newWidth, newHeight,
                                Image.SCALE_SMOOTH), 0, 0, null);
                // JPEGImageEncoder可适用于其他图片类型的转换
//                FileOutputStream out = new FileOutputStream(outputFileName);
//                JPEGImageEncoder encoder = JPEGCodec.createJPEGEncoder(out);
//                encoder.encode(tag);
//                out.close();
                String formatName = outputFileName.substring(outputFileName.lastIndexOf(".") + 1);
                ImageIO.write(tag, formatName, new File(outputFileName));
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return "ok";
    }

    /**
     * 按照左上角坐标和长宽截图图片
     *
     * @param inputFileName  源图片
     * @param outputFileName 目标图片
     * @param x              X坐标
     * @param y              Y坐标
     * @param width          宽度
     * @param height         长度
     * @return 结果，ok为成功
     */
    public static String cutPic(String inputFileName, String outputFileName, int x, int y, int width, int height) {
        try {
            // 获得源文件
            File file = new File(inputFileName);
            if (!file.exists()) {
                return "";
            }
            BufferedImage img = ImageIO.read(file);
            // 判断图片格式是否正确
            if (img.getWidth(null) == -1) {
                log.info(" can't read,retry!" + "<BR>");
                return "no";
            }
            img = img.getSubimage(x, y, width, height);
            String formatName = outputFileName.substring(outputFileName.lastIndexOf(".") + 1);
            ImageIO.write(img, formatName, new File(outputFileName));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return "ok";
    }

    /**
     * 将图片文件转化为字节数组字符串，并对其进行Base64编码处理
     *
     * @param imgFilePath 图片路径
     * @return 图片base64
     */
    public static String getImageStr(String imgFilePath) {
        byte[] data = null;
        // 读取图片字节数组
        try {
            InputStream in = new FileInputStream(imgFilePath);
            data = new byte[in.available()];
            in.read(data);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 对字节数组Base64编码
        Base64.Encoder encoder = Base64.getEncoder();
        // 返回Base64编码过的字节数组字符串
        return encoder.encodeToString(data);
    }

    /**
     * 将网络图片编码为base64
     *
     * @param url 图片URL
     * @return 图片Base64字符串
     */
    public static String getImageStr(URL url) {
        byte[] data = fromUrl(url);
        //对字节数组Base64编码
        Base64.Encoder encoder = Base64.getEncoder();
        String base64 = encoder.encodeToString(data);
        log.info("网络文件[{}]编码成base64字符串:[{}]" + url.toString() + base64);
        //返回Base64编码过的字节数组字符串
        return base64;
    }

    /**
     * 对字节数组字符串进行Base64解码并生成图片
     *
     * @param imgStr      图片base64码
     * @param imgFilePath 输出文件路径
     * @return 是否生成成功
     */
    public static boolean generateImage(String imgStr, String imgFilePath) {
        // 图像数据为空
        if (imgStr == null) {
            return false;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        try {
            // Base64解码
            byte[] bytes = decoder.decode(imgStr);
            // 生成jpeg图片
            OutputStream out = new FileOutputStream(imgFilePath);
            out.write(bytes);
            out.flush();
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 替换图片背景色
     *
     * @param imageFile   源图片
     * @param imgFilePath 处理后的文件路径
     * @return 是否成功
     */
    public static boolean colorImage(File imageFile, String imgFilePath) {
        try {
            // 定义一个RGB的数组，因为图片的RGB模式是由三个 0-255来表示的 比如白色就是(255,255,255)
            int[] rgb = new int[3];
            // 用来处理图片的缓冲流
            BufferedImage bi = null;

            // 用ImageIO将图片读入到缓冲中
            bi = ImageIO.read(imageFile);

            // 得到图片的长宽
            int width = bi.getWidth();
            int height = bi.getHeight();
            int minx = bi.getMinX();
            int miny = bi.getMinY();
            log.info("正在处理：" + imageFile.getName());

            /*
              这里是遍历图片的像素，因为要处理图片的背色，所以要把指定像素上的颜色换成目标颜色 这里 是一个二层循环，遍历长和宽上的每个像素
             */
            for (int i = minx; i < width; i++) {
                for (int j = miny; j < height; j++) {
                    // System.out.print(bi.getRGB(jw, ih));
                    // 得到指定像素（i,j)上的RGB值
                    int pixel = bi.getRGB(i, j);
                    // 分别进行位操作得到 r g b上的值
                    rgb[0] = (pixel & 0xff0000) >> 16;
                    rgb[1] = (pixel & 0xff00) >> 8;
                    rgb[2] = (pixel & 0xff);
                    // 进行换色操作，我这里是要把蓝底换成白底，那么就判断图片中rgb值是否在蓝色范围的像素
                    if (rgb[0] < 255 && rgb[0] > 105 && rgb[1] < 255 && rgb[1] > 105 && rgb[2] < 255 && rgb[2] > 105) {
                        // 这里是判断通过，则把该像素换成白色
                        bi.setRGB(i, j, 0xffffff);
                    }

                }
            }
            log.info("\t处理完毕：" + imageFile.getName());
            // 将缓冲对象保存到新文件中
            FileOutputStream ops = new FileOutputStream(new File(imgFilePath));
            ImageIO.write(bi, "jpg", ops);
            ops.flush();
            ops.close();
            return true;
        } catch (Exception e) {
            return false;
        }
        /*
         * BASE64Decoder decoder = new BASE64Decoder();
         *
         * // Base64解码 byte[] bytes = decoder.decodeBuffer(imgStr); for (int i =
         * 0; i < bytes.length; ++i) { if (bytes[i] < 0) {// 调整异常数据 bytes[i] +=
         * 256; } } // 生成jpeg图片 OutputStream out = new
         * FileOutputStream(imgFilePath); out.write(bytes); out.flush();
         * out.close(); return true; } catch (Exception e) { return false; }
         */
    }

    /**
     * HTML转图片
     *
     * @param htmText           html字符串
     * @param saveImageLocation 目标路径
     */
    public static void html2Image(String htmText, String saveImageLocation) {
        HtmlImageGenerator imageGenerator = new HtmlImageGenerator();
        try {
            imageGenerator.loadHtml(htmText);
            Thread.sleep(100);
            imageGenerator.getBufferedImage();
            Thread.sleep(2200);
            imageGenerator.saveAsImage(saveImageLocation);
            //imageGenerator.saveAsHtmlWithMap("hello-world.html", saveImageLocation);
            //不需要转换位图的，下面三行可以不要
            BufferedImage sourceImg = ImageIO.read(new File(saveImageLocation));
            sourceImg = transformGray24BitMap(sourceImg);
            ImageIO.write(sourceImg, "BMP", new File(saveImageLocation));
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("将HTML文件转换成图片异常");
        }
    }

    public static byte[] fromUrl(String imageUrl) {
        ByteArrayOutputStream baos = null;
        try {
            URL u = new URL(imageUrl);
            BufferedImage image = ImageIO.read(u);

            //convert BufferedImage to byte array
            baos = new ByteArrayOutputStream();
            ImageIO.write(image, "JPEG", baos);
            baos.flush();

            return baos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (baos != null) {
                try {
                    baos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public static byte[] fromUrl(URL url) {
        //将图片文件转化为字节数组字符串，并对其进行Base64编码处理
        log.info("图片的路径为:" + url.toString());
        //打开链接
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            //设置请求方式为"GET"
            conn.setRequestMethod("GET");
            //超时响应时间为5秒
            conn.setConnectTimeout(5 * 1000);
            //通过输入流获取图片数据
            InputStream inStream = conn.getInputStream();
            //得到图片的二进制数据，以二进制封装得到数据，具有通用性
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            //创建一个Buffer字符串
            byte[] buffer = new byte[1024];
            //每次读取的字符串长度，如果为-1，代表全部读取完毕
            int len = 0;
            //使用一个输入流从buffer里把数据读取出来
            while ((len = inStream.read(buffer)) != -1) {
                //用输出流往buffer里写入数据，中间参数代表从哪个位置开始读，len代表读取的长度
                outStream.write(buffer, 0, len);
            }
            //关闭输入流
            inStream.close();
            return outStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] fromFile(File file) {
        try {
            //通过输入流获取图片数据
            InputStream inStream = new FileInputStream(file);
            //得到图片的二进制数据，以二进制封装得到数据，具有通用性
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            //创建一个Buffer字符串
            byte[] buffer = new byte[1024];
            //每次读取的字符串长度，如果为-1，代表全部读取完毕
            int len = 0;
            //使用一个输入流从buffer里把数据读取出来
            while ((len = inStream.read(buffer)) != -1) {
                //用输出流往buffer里写入数据，中间参数代表从哪个位置开始读，len代表读取的长度
                outStream.write(buffer, 0, len);
            }
            //关闭输入流
            inStream.close();
            return outStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void html2Image(URL url, String saveImageLocation) {
        HtmlImageGenerator imageGenerator = new HtmlImageGenerator();
        try {
            imageGenerator.loadUrl(url);
            Thread.sleep(100);
            imageGenerator.getBufferedImage();
            Thread.sleep(200);
            imageGenerator.saveAsImage(saveImageLocation);
            //imageGenerator.saveAsHtmlWithMap("hello-world.html", saveImageLocation);
            //不需要转换位图的，下面三行可以不要
            BufferedImage sourceImg = ImageIO.read(new File(saveImageLocation));
            sourceImg = transformGray24BitMap(sourceImg);
            ImageIO.write(sourceImg, "BMP", new File(saveImageLocation));
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("将HTML文件转换成图片异常");
        }
    }

    /**
     * 转换成24位图的BMP
     *
     * @param image 原图BufferedImage
     * @return 位图BufferedImage
     */
    public static BufferedImage transformGray24BitMap(BufferedImage image) {
        int h = image.getHeight();
        int w = image.getWidth();
        // 定义数组，用来存储图片的像素
        int[] pixels = new int[w * h];
        int gray;
        PixelGrabber pg = new PixelGrabber(image, 0, 0, w, h, pixels, 0, w);
        try {
            // 读取像素值
            pg.grabPixels();
        } catch (InterruptedException e) {
            throw new RuntimeException("转换成24位图的BMP时，处理像素值异常");
        }

        // 扫描列
        for (int j = 0; j < h; j++) {
            // 扫描行
            for (int i = 0; i < w; i++) {
                // 由红，绿，蓝值得到灰度值
                gray = (int) (((pixels[w * j + i] >> 16) & 0xff) * 0.8);
                gray += (int) (((pixels[w * j + i] >> 8) & 0xff) * 0.1);
                gray += (int) (((pixels[w * j + i]) & 0xff) * 0.1);
                pixels[w * j + i] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }

        MemoryImageSource s = new MemoryImageSource(w, h, pixels, 0, w);
        Image img = Toolkit.getDefaultToolkit().createImage(s);
        //如果要转换成别的位图，改这个常量即可
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        buf.createGraphics().drawImage(img, 0, 0, null);
        return buf;
    }

    /**
     * 图片二值化
     *
     * @param sourceImgPath 原图片路径
     * @param targetImgPath 目标图片路径
     * @return 是否成功
     */
    public boolean binaryImage(String sourceImgPath, String targetImgPath) {
        try {
            File file = new File(sourceImgPath);
            if (!file.exists()) {
                return false;
            }

            BufferedImage image = ImageIO.read(file);

            int width = image.getWidth();
            int height = image.getHeight();

            BufferedImage binaryImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) {
                    int rgb = image.getRGB(i, j);
                    binaryImage.setRGB(i, j, rgb);
                }
            }
            String formatName = targetImgPath.substring(targetImgPath.lastIndexOf(".") + 1);
            ImageIO.write(binaryImage, formatName, new File(targetImgPath));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 图片灰度处理
     *
     * @param sourceImgPath 原图片路径
     * @param targetImgPath 目标图片路径
     * @return 是否成功
     */
    public boolean grayImage(String sourceImgPath, String targetImgPath){
        try {
            File file = new File(sourceImgPath);
            if (!file.exists()) {
                return false;
            }

            BufferedImage image = ImageIO.read(file);

            int width = image.getWidth();
            int height = image.getHeight();

            BufferedImage binaryImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) {
                    int rgb = image.getRGB(i, j);
                    binaryImage.setRGB(i, j, rgb);
                }
            }
            String formatName = targetImgPath.substring(targetImgPath.lastIndexOf(".") + 1);
            ImageIO.write(binaryImage, formatName, new File(targetImgPath));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
