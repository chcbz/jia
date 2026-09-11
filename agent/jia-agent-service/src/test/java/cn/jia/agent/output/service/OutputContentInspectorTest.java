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

    @Test void pkGarbageZeroEntryAndTruncatedCentralDirectoryAreRejected()throws Exception{
        for(byte[] malformed:List.of(
                new byte[]{0x50,0x4b,0x03,0x04,0,0,0,0},
                emptyZip(),
                Arrays.copyOf(zip(new Entry("ok.txt","ok".getBytes(StandardCharsets.UTF_8))),
                        zip(new Entry("ok.txt","ok".getBytes(StandardCharsets.UTF_8))).length-5))){
            OutputUploadException rejected=assertThrows(OutputUploadException.class,
                    ()->OutputContentInspector.requireAllowed("broken.zip","application/zip",malformed,true));
            assertEquals("OUTPUT_ARCHIVE_INVALID",rejected.code());
        }
    }

    @Test void directoryNamedEntriesCannotHideBytesLimitsOrNestedActiveContent()throws Exception{
        OutputUploadException oversized=assertThrows(OutputUploadException.class,
                ()->OutputContentInspector.requireAllowed("directory.zip","application/zip",
                        zip(new Entry("folder/",new byte[2049])),true,2048,16*1024));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",oversized.code());

        byte[] hidden=zip(new Entry("payload.html","<html>bad</html>".getBytes(StandardCharsets.UTF_8)));
        OutputUploadException hiddenArchive=assertThrows(OutputUploadException.class,
                ()->OutputContentInspector.requireAllowed("directory.zip","application/zip",
                        zip(new Entry("folder/",hidden)),true));
        assertEquals("OUTPUT_ARCHIVE_INVALID",hiddenArchive.code());
    }

    @Test void centralDirectoryMustReferenceEveryExactLocalRecordOnce()throws Exception{
        byte[] valid=zip(new Entry("visible.txt","ok".getBytes(StandardCharsets.UTF_8)),new Entry("hidden.txt",new byte[1024]));
        OutputUploadException overLimit=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("valid.zip","application/zip",valid,true,64,4096));
        assertEquals("OUTPUT_ARCHIVE_LIMIT",overLimit.code());
        for(byte[] inconsistent:List.of(unlistSecondCentralEntry(valid),mismatchFirstLocalName(valid),mismatchFirstLocalCrc(valid),duplicateFirstLocalOffset(valid),wrongEocdCount(valid),wrongCentralOffset(valid))){
            OutputUploadException rejected=assertThrows(OutputUploadException.class,()->OutputContentInspector.requireAllowed("hidden.zip","application/zip",inconsistent,true,64,4096));
            assertEquals("OUTPUT_ARCHIVE_INVALID",rejected.code());
        }
    }

    private static byte[] zip(Entry...entries)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Entry entry:entries){zip.putNextEntry(new ZipEntry(entry.name));zip.write(entry.bytes);zip.closeEntry();}}return out.toByteArray();}
    private static byte[] emptyZip()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream ignored=new ZipOutputStream(out)){}return out.toByteArray();}
    private static byte[] unlistSecondCentralEntry(byte[] valid){int eocd=find(valid,0x06054b50,valid.length-22),central=(int)u32(valid,eocd+16),firstLength=46+u16(valid,central+28)+u16(valid,central+30)+u16(valid,central+32);byte[] malformed=new byte[central+firstLength+22];System.arraycopy(valid,0,malformed,0,central+firstLength);System.arraycopy(valid,eocd,malformed,central+firstLength,22);int newEocd=central+firstLength;put16(malformed,newEocd+8,1);put16(malformed,newEocd+10,1);put32(malformed,newEocd+12,firstLength);put32(malformed,newEocd+16,central);return malformed;}
    private static byte[] mismatchFirstLocalName(byte[] valid){byte[] malformed=valid.clone();malformed[30]=(byte)(malformed[30]=='v'?'x':'v');return malformed;}
    private static byte[] mismatchFirstLocalCrc(byte[] valid){byte[] malformed=valid.clone();put32(malformed,14,1);return malformed;}
    private static byte[] duplicateFirstLocalOffset(byte[] valid){byte[] malformed=valid.clone();int eocd=find(malformed,0x06054b50,malformed.length-22),central=(int)u32(malformed,eocd+16),second=central+46+u16(malformed,central+28)+u16(malformed,central+30)+u16(malformed,central+32);put32(malformed,second+42,u32(malformed,central+42));return malformed;}
    private static byte[] wrongEocdCount(byte[] valid){byte[] malformed=valid.clone();int eocd=find(malformed,0x06054b50,malformed.length-22);put16(malformed,eocd+8,1);put16(malformed,eocd+10,1);return malformed;}
    private static byte[] wrongCentralOffset(byte[] valid){byte[] malformed=valid.clone();int eocd=find(malformed,0x06054b50,malformed.length-22);put32(malformed,eocd+16,u32(malformed,eocd+16)+1);return malformed;}
    private static int find(byte[] bytes,int signature,int start){for(int i=start;i>=0;i--)if(u32(bytes,i)==Integer.toUnsignedLong(signature))return i;throw new IllegalArgumentException("signature");}
    private static int u16(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8);}
    private static long u32(byte[] b,int at){return Integer.toUnsignedLong((b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|((b[at+3]&255)<<24));}
    private static void put16(byte[] b,int at,int v){b[at]=(byte)v;b[at+1]=(byte)(v>>>8);}
    private static void put32(byte[] b,int at,long v){for(int i=0;i<4;i++)b[at+i]=(byte)(v>>>(8*i));}
    private static byte[] repeat(byte[] block,int count){byte[] bytes=new byte[Math.multiplyExact(block.length,count)];for(int i=0;i<count;i++)System.arraycopy(block,0,bytes,i*block.length,block.length);return bytes;}
    private record Entry(String name,byte[] bytes){}
}
