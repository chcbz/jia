package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputUploadException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputContentInspectorTest {
    @Test void incompleteUtf8CodePointAtSampleBoundaryIsDeferredUntilFullVerification(){
        byte[] sample=new byte[2*1024*1024];Arrays.fill(sample,(byte)'a');sample[sample.length-1]=(byte)0xe4;
        assertEquals("text/plain",OutputContentInspector.requireAllowed("report.txt","text/plain",sample,false));
        OutputUploadException rejected=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("report.txt","text/plain",sample,true));
        assertEquals("OUTPUT_TEXT_ENCODING_INVALID",rejected.code());
    }
    @Test void dataDescriptorZipCountsActuallyExpandedBytes()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){byte[] block=new byte[1024*1024];for(String name:List.of("first.bin","second.bin")){zip.putNextEntry(new ZipEntry(name));for(int i=0;i<46;i++)zip.write(block);zip.closeEntry();}}
        OutputUploadException rejected=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("large.zip","application/zip",bytes.toByteArray(),true));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",rejected.code());
    }

    @Test void oneMemberCannotExceedConfiguredFiftyMiBScanBoundary()throws Exception{
        byte[] archive=zip(new Entry("single.bin",new byte[51*1024*1024]));
        OutputUploadException rejected=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("single.zip","application/zip",archive,true));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",rejected.code());
    }

    @Test void wholeTreeBudgetIncludesRootArchiveBytes()throws Exception{
        byte[] payload=new byte[1024],archive=zip(new Entry("payload.bin",payload));long exact=Math.addExact(archive.length,payload.length);
        assertEquals("application/zip",OutputContentInspector.requireAllowed("root.zip","application/zip",archive,true,2048,exact));
        OutputUploadException rejected=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("root.zip","application/zip",archive,true,2048,exact-1));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",rejected.code());
    }

    @Test void nestedZipUsesOneSharedBudgetAndRejectsDepthAndOtherArchiveMagic()throws Exception{
        byte[] block=new byte[1024*1024];byte[] oversizedTree=zip(new Entry("one.bin",repeat(block,46)),new Entry("two.bin",repeat(block,46)));
        OutputUploadException tree=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("nested.zip","application/zip",zip(new Entry("nested.zip",oversizedTree)),true));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",tree.code());

        byte[] level4=zip(new Entry("ok.txt","ok".getBytes(StandardCharsets.UTF_8)));
        byte[] level3=zip(new Entry("level4.zip",level4));
        byte[] level2=zip(new Entry("level3.zip",level3));
        byte[] depth4=zip(new Entry("level2.zip",level2));
        OutputUploadException depth=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("depth.zip","application/zip",depth4,true));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",depth.code());

        OutputUploadException gzip=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("nested.zip","application/zip",zip(new Entry("payload.bin",new byte[]{0x1f,(byte)0x8b,8,0})),true));
        assertEquals("OUTPUT_ARCHIVE_INVALID",gzip.code());
    }

    @Test void nestedZipWithinDepthAndSharedBudgetIsAccepted()throws Exception{
        byte[] level3=zip(new Entry("ok.txt","ok".getBytes(StandardCharsets.UTF_8)));
        byte[] level2=zip(new Entry("level3.zip",level3));
        byte[] depth3=zip(new Entry("level2.zip",level2));
        assertEquals("application/zip",OutputContentInspector.requireAllowed("nested.zip","application/zip",depth3,true));
    }

    private static byte[] zip(Entry...entries)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Entry entry:entries){zip.putNextEntry(new ZipEntry(entry.name));zip.write(entry.bytes);zip.closeEntry();}}return out.toByteArray();}
    private static byte[] repeat(byte[] block,int count){byte[] bytes=new byte[Math.multiplyExact(block.length,count)];for(int i=0;i<count;i++)System.arraycopy(block,0,bytes,i*block.length,block.length);return bytes;}
    private record Entry(String name,byte[] bytes){}
}
