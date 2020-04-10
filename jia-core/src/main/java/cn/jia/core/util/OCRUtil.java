package cn.jia.core.util;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.sun.media.imageio.plugins.tiff.TIFFImageWriteParam;

/**
 * 调用本地tesseractOCR识别图片信息
 * @author evang
 *
 */
public class OCRUtil {
	private final static  String LANG_OPTION = "-l"; // 英文字母小写l，并非数字1
	private final static  String EOL = System.getProperty("line.separator");
	private static String tessPath = "D:\\software\\Tesseract-OCR";
//	private String tessPath = new File("tesseract").getAbsolutePath();
	private static String OS = System.getProperty("os.name").toLowerCase();

	public static String recognizeText(File imageFile, String imageFormat)
			throws Exception {
		File tempImage = createImage(imageFile, imageFormat);
		File outputFile = new File(imageFile.getParentFile(), "output");
		StringBuffer strB = new StringBuffer();
		List<String> cmd = new ArrayList<String>();
		if (OS.indexOf("windows") >= 0) {
			cmd.add(tessPath + "\\tesseract");
		} else if (OS.indexOf("linux") >= 0) {
			cmd.add("tesseract");
		} else {
			cmd.add("tesseract");
		}
		cmd.add("");
		cmd.add(outputFile.getName());
		cmd.add(LANG_OPTION);
//		 cmd.add("chi_sim");
		cmd.add("eng");

		ProcessBuilder pb = new ProcessBuilder();
		pb.directory(imageFile.getParentFile());

		cmd.set(1, tempImage.getName());
		pb.command(cmd);
		pb.redirectErrorStream(true);

		Process process = pb.start();
		// tesseract.exe 1.jpg 1 -l chi_sim
		int w = process.waitFor();

		// 删除临时正在工作文件
		tempImage.delete();

		if (w == 0) {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					new FileInputStream(outputFile.getAbsolutePath() + ".txt"),
					"UTF-8"));

			String str;
			while ((str = in.readLine()) != null) {
				strB.append(str).append(EOL);
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
			tempImage.delete();
			throw new RuntimeException(msg);
		}
		new File(outputFile.getAbsolutePath() + ".txt").delete();
		return strB.toString();
	}
	
	/** 
     * 图片文件转换为tif格式 
     * @param imageFile 文件路径 
     * @param imageFormat 文件扩展名 
     * @return 
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
            IIOImage image = new IIOImage(bi,null,reader.getImageMetadata(0));  
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
        StringBuffer strB = new StringBuffer(path);  
        strB.insert(path.lastIndexOf('.'),0);  
        return new File(strB.toString().replaceFirst("(?<=//.)(//w+)$", "tif"));  
    }

	public static void main(String[] args) {
		String path = "C:\\Users\\chcbz\\Desktop\\11.png";
		
//		Base64Image.GenerateImage(base64Image, path);
		
		System.out.println(System.getProperty("user.dir"));
		
		try {
			String valCode = OCRUtil.recognizeText(new File(path), "png");
			System.out.println(valCode);
			Pattern pattern = Pattern.compile("([A-Z]\\d{8})");
			Matcher matcher = pattern.matcher(valCode);
			if (matcher.find()) {
				System.out.println(matcher.group(1));
			} 
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}