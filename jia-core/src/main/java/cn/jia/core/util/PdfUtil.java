package cn.jia.core.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.fit.pdfdom.PDFDomTree;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class PdfUtil {


    /**
     * PDF转HTML
     * @param inPdfPath PDF文件路径
     */
    public static void pdfToHtml(String inPdfPath, String outHtmlPath)  {
        // try() 写在()里面会自动关闭流
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outHtmlPath)), StandardCharsets.UTF_8));){
            //加载PDF文档
            PDDocument document = PDDocument.load(new File(inPdfPath));
            PDFDomTree pdfDomTree = new PDFDomTree();
            pdfDomTree.writeText(document, out);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        PdfUtil.pdfToHtml("C:\\Users\\Think\\Desktop\\检验报告.安徽省金陵塑业有限公司2020皖检xf字第0032号.pdf", "D:\\tmp\\target.html");
    }
}
