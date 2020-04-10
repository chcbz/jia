package cn.jia.core.util;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class QRCodeUtil {

	/**
	 * 生成二维码图片
	 * @param filePath 文件路径
	 * @param fileName 文件名
	 * @param content 二维码内容
	 * @param width 图片宽度
	 * @param height 图片高度
	 */
	public static void encode(String filePath, String fileName, String content, int width, int height){
		Map<EncodeHintType, Object> hints = new HashMap<>();
		hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
		hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
		hints.put(EncodeHintType.MARGIN, 1);
		BitMatrix bitMatrix;
		try {
			bitMatrix = new MultiFormatWriter().encode(content,
					BarcodeFormat.QR_CODE, width, height, hints);// 生成矩阵
			File pathFile = new File(filePath);
			if(!pathFile.isDirectory()){
				//noinspection ResultOfMethodCallIgnored
				pathFile.mkdirs();
			}
			Path path = FileSystems.getDefault().getPath(filePath, fileName);
			String format = fileName.substring(fileName.lastIndexOf(".")+1);
			MatrixToImageWriter.writeToPath(bitMatrix, format, path);// 输出图像
		} catch (WriterException | IOException e) {
			e.printStackTrace();
		}

	}
	
	/**
	 * 读取二维码内容
	 * @param filePath 文件路径
	 * @param fileName 文件名
	 * @return 二维码内容
	 */
	public static String decode(String filePath, String fileName) {
		BufferedImage image;
		String qrtext = "";
		try {
			image = ImageIO.read(new File(filePath,fileName));
			LuminanceSource source = new BufferedImageLuminanceSource(image);
			Binarizer binarizer = new HybridBinarizer(source);
			BinaryBitmap binaryBitmap = new BinaryBitmap(binarizer);
			Map<DecodeHintType, Object> hints = new HashMap<>();
			hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
			Result result = new MultiFormatReader().decode(binaryBitmap, hints);// 对图像进行解码
			qrtext = result.getText();
		} catch (IOException | NotFoundException e) {
			e.printStackTrace();
		}
		return qrtext;
	}

	/**
	 * 二维码绘制logo
	 * @param orgiImg 二维码图片文件
	 * @param logoImg logo图片文件
	 * */
	public static BufferedImage composeLogo(File orgiImg, File logoImg){
		BufferedImage matrixImage = null;
		try{
			if(!orgiImg.isFile() || !logoImg.isFile()){
				System.out.println("输入非图片");
				return null;
			}
			//读取二维码图片
			matrixImage = ImageIO.read(orgiImg);
			// 读取二维码图片，并构建绘图对象
			Graphics2D g2 = matrixImage.createGraphics();

			int matrixWidth = matrixImage.getWidth();
			int matrixHeigh = matrixImage.getHeight();

			// 读取Logo图片
			BufferedImage logo = ImageIO.read(logoImg);

			//设置二维码大小，太大，会覆盖二维码，此处20%
			int logoWidth = Math.min(logo.getWidth(null), matrixImage.getWidth() * 19 / 100);
			int logoHeight = Math.min(logo.getHeight(null), matrixImage.getHeight() * 19 / 100);
			//设置logo图片放置位置
			//中心
			int x = (matrixImage.getWidth() - logoWidth) / 2;
			int y = (matrixImage.getHeight() - logoHeight) / 2;
			//开始合并绘制图片
			g2.drawImage(logo, x, y, logoWidth, logoHeight, null);

			BasicStroke stroke = new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
			g2.setStroke(stroke);// 设置笔画对象
			//指定弧度的圆角矩形
			RoundRectangle2D.Float round = new RoundRectangle2D.Float(matrixWidth/5F*2, matrixHeigh/5F*2, matrixWidth/5F, matrixHeigh/5F,20,20);
			g2.setColor(Color.white);
			g2.draw(round);// 绘制圆弧矩形

			//设置logo 有一道灰色边框
			BasicStroke stroke2 = new BasicStroke(1,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
			g2.setStroke(stroke2);// 设置笔画对象
			RoundRectangle2D.Float round2 = new RoundRectangle2D.Float(matrixWidth/5F*2+2, matrixHeigh/5F*2+2, matrixWidth/5F-4, matrixHeigh/5F-4,20,20);
			g2.setColor(new Color(128,128,128));
			g2.draw(round2);// 绘制圆弧矩形

			g2.dispose();
			matrixImage.flush() ;
		}catch(Exception e){
			System.out.println("二维码绘制logo失败");
		}
		return matrixImage;
	}

	/**
	 * 二维码输出到文件
	 * @param orgiImg 二维码图片文件
	 * @param logoImg logo图片文件
	 * @param file 输出文件
	 * */
	public static void composeLogo(File orgiImg, File logoImg, File file) {
		BufferedImage image = composeLogo(orgiImg, logoImg);
		try {
			String fileName = file.getName();
			String format = fileName.substring(fileName.lastIndexOf(".")+1);
			ImageIO.write(Objects.requireNonNull(image), format, file);
		} catch (IOException e) {
			System.out.println("二维码写入文件失败"+e.getMessage());
		}
	}
	/**
	 * 二维码流式输出
	 * @param orgiImg 二维码图片文件
	 * @param logoImg logo图片文件
	 * @param format 图片格式
	 * @param stream 输出流
	 * */
	public static void composeLogo(File orgiImg, File logoImg, String format, OutputStream stream){
		BufferedImage image = composeLogo(orgiImg, logoImg);
		try {
			ImageIO.write(Objects.requireNonNull(image), format, stream);
		} catch (IOException e) {
			System.out.println("二维码写入流失败"+e.getMessage());
		}
	}
	
	public static void main(String[] args) throws IOException {
//		encode("C:\\WorkSpace", "qr.png", "http://www.qq.com", 200, 200);
//		System.out.println(decode("C:\\WorkSpace", "qr.png"));
		String shareUrl = "https://open.weixin.qq.com/";
		File qrFile = File.createTempFile("wx-qrcode", ".jpg");
		QRCodeUtil.encode(qrFile.getParent(), qrFile.getName(), shareUrl, 344, 344);
		File logFile = new File("C:\\Users\\Think\\Pictures\\fanlibao.jpg");
		composeLogo(qrFile, logFile, qrFile);
	}
}
