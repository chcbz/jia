package cn.jia.core.util;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 文件操作工具类
 *
 * @author lzh
 */
public class FileUtil {

    /**
     * 获取文件名称<br>
     * 1：example.jpg-->example<br>
     * 2：d:\folder1\folder2\exmple.jpg-->example
     *
     * @param fileName 文件名
     * @return 文件名称
     */
    public static String getName(String fileName) {
        String path = fileName.substring(0, fileName.lastIndexOf("."));
        return path.substring(path.lastIndexOf("\\") + 1);
    }

    /**
     * 获取文件扩展名
     *
     * @param fileName 文件名
     * @return 扩展名
     */
    public static String getExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    /**
     * 创建文件，如果不设置缓冲区，则默认为1024*1024=1M
     *
     * @param inputStream 文件流
     * @param newFilePath 新文件路径
     * @param buffer 	缓冲区
     * @return 是否创建成功
     */
    public static boolean create(InputStream inputStream, String newFilePath, byte[] buffer) {
        try {
            //此处抛出异常是为了有可能配合数据库使用时：事务的回滚
            if (inputStream == null) {
                throw new RuntimeException("文件流为空");
            }

            File file = new File(newFilePath);
            if (file.exists()) {
                throw new RuntimeException("指定路径的文件已经存在");
            }
            if (!file.getParentFile().isDirectory()) {
                file.getParentFile().mkdirs();
            }
            //设置默认
            byte[] bufferTemp = new byte[1024 * 1024];
            if (buffer != null && buffer.length > 0) {
                bufferTemp = buffer;
            }

            file.createNewFile();
            FileOutputStream outputStream = new FileOutputStream(file);
            int len = 0;
            while ((len = inputStream.read(bufferTemp)) != -1) {
                outputStream.write(bufferTemp, 0, len);
                //outputStream.flush();
            }
            inputStream.close();
            outputStream.close();

            return true;
        } catch (IOException e) {
            throw new RuntimeException("创建文件失败：" + e);
        }
    }

    /**
     * 创建文件。缓冲区默认为1024*1024=1M
     *
     * @param inputStream 文件流
     * @param newFilePath 新文件路径
     * @return 是否创建成功
     */
    public static boolean create(InputStream inputStream, String newFilePath) {
        return create(inputStream, newFilePath, null);
    }

	/**
	 * 创建文件。缓冲区默认为1024*1024=1M
	 *
	 * @param b 文件流
	 * @param newFilePath 新文件路径
	 * @return 是否创建成功
	 */
	public static boolean create(byte[] b, String newFilePath) {
		return create(new ByteArrayInputStream(b), newFilePath, null);
	}

    /**
     * 创建文件。缓冲区默认为1024*1024=1M
     *
     * @param sourceFilePath 文件路径
     * @param newFilePath 新文件路径
     * @return 是否创建成功
     */
    public static boolean create(String sourceFilePath, String newFilePath) {
        try {
            File file = new File(sourceFilePath);
            if (!file.exists() || !file.isFile()) {
                throw new RuntimeException("未能找到指定的文件");
            }
            return create(new FileInputStream(file), newFilePath);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("创建文件失败：" + e);
        }
    }

    /**
     * 创建文件。缓冲区默认为1024*1024=1M
     *
     * @param sourceFilePath 文件路径
     * @param newFilePath 新文件路径
     * @param buffer 缓冲区
     * @return 是否创建成功
     */
    public static boolean create(String sourceFilePath, String newFilePath, byte[] buffer) {
        try {
            File file = new File(sourceFilePath);
            if (!file.exists() || !file.isFile()) {
                throw new RuntimeException("未能找到指定的文件");
            }
            return create(new FileInputStream(file), newFilePath, buffer);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("创建文件失败：" + e);
        }
    }

    /**
     * 移动文件
     *
     * @param sourceFilePath 源文件路径
     * @param dirPath 目标文件夹路径
     * @return 是否移动成功
     */
    public static boolean move(String sourceFilePath, String dirPath) {
        File file = new File(sourceFilePath);
        File directory = new File(dirPath);

        if (file.exists() && file.isFile() && directory.isDirectory()) {

        }

        return false;
    }

    /**
     * 删除文件
     *
     * @param filePath 文件路径
     * @return 是否删除成功
     */
    public static boolean delete(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                file.delete();
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("删除文件时遇到异常[" + filePath + "]:" + e);
        }
        return false;
    }

    /**
     * 按字节读取字符串
     * 个人感觉最好的方式，（一次读完）读字节就读字节吧，读完转码一次不就好了
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    public static String readString(String filePath) {
        String str = "";
        File file = new File(filePath);
        try {
            FileInputStream in = new FileInputStream(file);
            // size 为字串的长度 ，这里一次性读完
            int size = in.available();
            byte[] buffer = new byte[size];
            in.read(buffer);
            in.close();
            str = new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
        return str;
    }

    /**
     * 创建目录
     *
     * @param path 路径
     * @return 是否创建成功
     */
    public static boolean mkdirs(String path) {
        File pathFile = new File(path);
        if (!pathFile.isDirectory()) {
            return pathFile.mkdirs();
        }
        return false;
    }

}
