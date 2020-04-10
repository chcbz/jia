package cn.jia.core.util;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.apache.poi.xwpf.converter.core.FileImageExtractor;
import org.apache.poi.xwpf.converter.core.FileURIResolver;
import org.apache.poi.xwpf.converter.xhtml.XHTMLConverter;
import org.apache.poi.xwpf.converter.xhtml.XHTMLOptions;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

public class WordUtil {

    /**
     * 2003版本word转换成html
     * @param srcFile word文件路径
     * @param targetFile html文件路径
     * @param imgPath 图片文件保存地址
     * @param imgUrl 图片src地址
     */
    public static void wordToHtml(String srcFile, String targetFile, String imgPath, String imgUrl) {
        File f = new File(srcFile);
        if (f.getName().endsWith(".docx") || f.getName().endsWith(".DOCX")) {
            word2007ToHtml(srcFile, targetFile, imgPath, imgUrl);
        } else {
            word2003ToHtml(srcFile, targetFile, imgPath, imgUrl);
        }
    }

    /**
     * 2003版本word转换成html
     * @param srcFile word文件路径
     * @param targetFile html文件路径
     * @param imgPath 图片文件保存地址
     * @param imgUrl 图片src地址
     */
    public static void word2003ToHtml(String srcFile, String targetFile, String imgPath, String imgUrl) {
        try (InputStream input = new FileInputStream(srcFile);) {
            HWPFDocument wordDocument = new HWPFDocument(input);
            WordToHtmlConverter wordToHtmlConverter = new WordToHtmlConverter(DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
            //设置图片存放的位置
            wordToHtmlConverter.setPicturesManager((content, pictureType, suggestedName, widthInches, heightInches) -> {
                File imgPath1 = new File(imgPath);
                if(!imgPath1.exists()){//图片目录不存在则创建
                    imgPath1.mkdirs();
                }

                File file1 = new File(imgPath + "/" + suggestedName);
                try {
                    OutputStream os = new FileOutputStream(file1);
                    os.write(content);
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return  imgUrl + "/" + suggestedName;
            });

            //解析word文档
            wordToHtmlConverter.processDocument(wordDocument);
            Document htmlDocument = wordToHtmlConverter.getDocument();

            OutputStream outStream = new FileOutputStream(targetFile);

            DOMSource domSource = new DOMSource(htmlDocument);
            StreamResult streamResult = new StreamResult(outStream);

            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer serializer = factory.newTransformer();
            serializer.setOutputProperty(OutputKeys.ENCODING, "utf-8");
            serializer.setOutputProperty(OutputKeys.INDENT, "yes");
            serializer.setOutputProperty(OutputKeys.METHOD, "html");

            serializer.transform(domSource, streamResult);
            outStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 2007版本word转换成html
     * @param srcFile word文件路径
     * @param targetFile html文件路径
     * @param imgPath 图片文件保存地址
     * @param imgUrl 图片src地址
     */
    public static void word2007ToHtml(String srcFile, String targetFile, String imgPath, String imgUrl) {
        File f = new File(srcFile);

        if (!f.exists()) {
            System.out.println("Sorry File does not Exists!");
        } else {

            try (InputStream in = new FileInputStream(f);) {
                // 1) 加载word文档生成 XWPFDocument对象
                XWPFDocument document = new XWPFDocument(in);

                // 2) 解析 XHTML配置 (这里设置IURIResolver来设置图片存放的目录)
                File imageFolderFile = new File(imgPath);
                XHTMLOptions options = XHTMLOptions.create().URIResolver(new FileURIResolver(imageFolderFile));
                options.setExtractor(new FileImageExtractor(imageFolderFile));
                options.URIResolver(uri -> imgUrl + "/" + uri);
                options.setIgnoreStylesIfUnused(false);
                options.setFragment(true);

                // 3) 将 XWPFDocument转换成XHTML
                OutputStream out = new FileOutputStream(targetFile);
                XHTMLConverter.getInstance().convert(document, out, options);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        WordUtil.wordToHtml("C:\\Users\\Think\\Desktop\\消息通知模板.docx", "D:\\tmp\\star.html", "D:\\tmp\\image", "https://test.12349.com/images");
    }
}
