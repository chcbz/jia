package cn.jia.core.util;


import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;

/**
 * 调用本地opencv库进行图片处理
 * 从官网下载并安装最新版https://opencv.org/releases/，当前装的是4.5.5版
 * 设置环境变量OPENCV_HOME={opencv安装目录}\build
 * 设置环境变量Path=$Path;$OPENCV_HOME/x64/vc15/bin;$OPENCV_HOME/java/x64;
 *
 * @author evang
 */
public class OpenCvUtil {
    /**
     * 判断原图中是否包含模板图片
     *
     * @param sourceImage 原图
     * @param templateImage 模板图片
     * @return 是否包含模板图片
     */
    public static boolean includeImage(File sourceImage, File templateImage) {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        Mat source, template;
        //将文件读入为OpenCV的Mat格式
        source = Imgcodecs.imread(sourceImage.getAbsolutePath());
        template = Imgcodecs.imread(templateImage.getAbsolutePath());
        Point matchLoc = getMatchPoint(source, template);
        if (matchLoc == null) {
            return false;
        }
        return matchLoc.x != 0 || matchLoc.y != 0;
    }

    /**
     * 在原图中圈出模板图片的位置
     *
     * @param sourceImage 原图
     * @param templateImage 模板图片
     * @return 结果图片
     */
    public static File tagMatchImage(File sourceImage, File templateImage) {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        Mat source, template;
        //将文件读入为OpenCV的Mat格式
        source = Imgcodecs.imread(sourceImage.getAbsolutePath());
        template = Imgcodecs.imread(templateImage.getAbsolutePath());
        Point matchLoc = getMatchPoint(source, template);
        if (matchLoc != null) {
            //在原图上的对应模板可能位置画一个绿色矩形
            Imgproc.rectangle(source, matchLoc, new Point(matchLoc.x + template.width(), matchLoc.y + template.height()), new Scalar(0, 255, 0));
        }
        //将结果输出到对应位置
        String outputPath = sourceImage.getParent();
        String resultFileName = FileUtil.getName(sourceImage.getName()) + "_result";
        String fileExtension = FileUtil.getExtension(sourceImage.getName());
        File resultFile = new File(outputPath + "/" + resultFileName + "." + fileExtension);
        Imgcodecs.imwrite(resultFile.getAbsolutePath(), source);
        return resultFile;
    }

    private static Point getMatchPoint(Mat source, Mat template) {
        //创建于原图相同的大小，储存匹配度
        Mat result = Mat.zeros(source.rows() - template.rows() + 1, source.cols() - template.cols() + 1, CvType.CV_32FC1);
        //调用模板匹配方法
        Imgproc.matchTemplate(source, template, result, Imgproc.TM_SQDIFF_NORMED);
        //规格化
        Core.normalize(result, result, 0, 1, Core.NORM_MINMAX, -1);
        //获得最可能点，MinMaxLocResult是其数据格式，包括了最大、最小点的位置x、y
        Core.MinMaxLocResult mlr = Core.minMaxLoc(result);
        return mlr.minVal == 0 ? mlr.minLoc : null;
    }

    public static void threshold(String sourceImgPath, String targetImgPath) {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        // 加载时灰度
        Mat src = Imgcodecs.imread(sourceImgPath, Imgcodecs.IMREAD_GRAYSCALE);
        Mat target = new Mat();
        //灰度图像二值化
        Imgproc.threshold(src, target, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        // 保存二值化后图片
        Imgcodecs.imwrite(targetImgPath, target);
    }
}