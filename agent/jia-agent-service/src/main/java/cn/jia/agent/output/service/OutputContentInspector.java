package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputUploadException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class OutputContentInspector {
    private static final Set<String> TEXT = Set.of("text/plain","text/markdown","text/csv","application/json");
    private static final long DEFAULT_ARCHIVE_MEMBER_MAX_BYTES=50L*1024*1024;
    private static final long DEFAULT_ARCHIVE_TREE_MAX_BYTES=90L*1024*1024;
    private static final int ARCHIVE_MAX_ENTRIES=2048,ARCHIVE_MAX_DEPTH=3,SNIFF_BYTES=512;

    static String requireAllowed(String name,String declared,byte[] bytes,boolean complete) {
        return requireAllowed(name,declared,bytes,complete,DEFAULT_ARCHIVE_MEMBER_MAX_BYTES,DEFAULT_ARCHIVE_TREE_MAX_BYTES);
    }

    static String requireAllowed(String name,String declared,byte[] bytes,boolean complete,long memberMaxBytes,long treeMaxBytes) {
        return requireAllowed(name,declared,bytes,complete,memberMaxBytes,treeMaxBytes,null);
    }

    static String requireAllowed(String name,String declared,byte[] bytes,boolean complete,long memberMaxBytes,long treeMaxBytes,Path tempDirectory) {
        String ext=extension(name); String mime=normalize(declared);
        if(mime==null)throw rejected("OUTPUT_MIME_UNSUPPORTED");
        if(TEXT.contains(mime)){
            requireText(bytes,complete);
            String lower=new String(bytes,StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if(lower.startsWith("<!doctype html")||lower.startsWith("<html")||lower.startsWith("<svg"))throw rejected("OUTPUT_ACTIVE_CONTENT_REJECTED");
            if(!textExtensionMatches(ext,mime))throw rejected("OUTPUT_TYPE_MISMATCH");
            return mime;
        }
        if(mime.equals("image/png")&&starts(bytes,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)&&ext.equals("png"))return mime;
        if(mime.equals("image/jpeg")&&starts(bytes,0xff,0xd8,0xff)&&Set.of("jpg","jpeg").contains(ext))return mime;
        if(mime.equals("image/webp")&&ascii(bytes,0,"RIFF")&&ascii(bytes,8,"WEBP")&&ext.equals("webp"))return mime;
        if(mime.equals("application/pdf")&&ascii(bytes,0,"%PDF-")&&ext.equals("pdf"))return mime;
        if(isExecutable(bytes))throw rejected("OUTPUT_EXECUTABLE_REJECTED");
        if(isZipMime(mime)&&starts(bytes,0x50,0x4b))return requireZip(ext,mime,bytes,complete,memberMaxBytes,treeMaxBytes,tempDirectory);
        throw rejected("OUTPUT_TYPE_MISMATCH");
    }

    private static String requireZip(String ext,String declared,byte[] bytes,boolean complete,long memberMaxBytes,long treeMaxBytes,Path tempDirectory){
        if(!complete&&Set.of("docx","xlsx","pptx","zip").contains(ext))return declared;
        ArchiveBudget budget=new ArchiveBudget(memberMaxBytes,treeMaxBytes,bytes.length,tempDirectory);Set<String> names=new HashSet<>();
        inspectRootZip(bytes,budget,names);
        if(budget.activeContent)throw rejected("OUTPUT_ACTIVE_CONTENT_REJECTED");
        boolean word=names.contains("word/document.xml"), sheet=names.contains("xl/workbook.xml"), slides=names.contains("ppt/presentation.xml");
        if(word&&ext.equals("docx")&&declared.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))return declared;
        if(sheet&&ext.equals("xlsx")&&declared.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))return declared;
        if(slides&&ext.equals("pptx")&&declared.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation"))return declared;
        if(!word&&!sheet&&!slides&&ext.equals("zip")&&declared.equals("application/zip"))return declared;
        throw rejected("OUTPUT_TYPE_MISMATCH");
    }

    private static void inspectRootZip(byte[] bytes,ArchiveBudget budget,Set<String> rootNames){
        Path directory=null,file=null;
        try{
            directory=createPrivateDirectory(budget.tempDirectory);file=createPrivateFile(directory);
            Files.write(file,bytes);inspectZipFile(file,1,budget,rootNames,true);
        }catch(OutputUploadException e){throw e;}catch(ZipException e){throw rejected("OUTPUT_ARCHIVE_INVALID");}
        catch(IOException|ArithmeticException e){throw new ArchiveIoException(e);}
        finally{deleteTemporary(file,directory);}
    }

    private static void inspectZipFile(Path file,int depth,ArchiveBudget budget,Set<String> rootNames,boolean root)throws IOException{
        int files=0;Set<String> archiveNames=new HashSet<>();
        try(ZipFile zip=new ZipFile(file.toFile(),StandardCharsets.UTF_8)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();
                if(++budget.entries>ARCHIVE_MAX_ENTRIES)throw rejected("OUTPUT_ARCHIVE_LIMIT");
                if(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED)throw rejected("OUTPUT_ARCHIVE_INVALID");
                String name=entry.getName().replace('\\','/').toLowerCase(Locale.ROOT);
                if(name.isEmpty()||!archiveNames.add(name))throw rejected("OUTPUT_ARCHIVE_INVALID");
                try(InputStream input=zip.getInputStream(entry)){
                    EntryReader reader=new EntryReader(input,entry,budget);byte[] prefix=reader.readPrefix();
                    if(entry.isDirectory()){
                        reader.drain(null);reader.verify();
                        if(reader.count()!=0)throw rejected("OUTPUT_ARCHIVE_INVALID");
                        continue;
                    }
                    files++;if(root)rootNames.add(name);
                    if(name.contains("vbaproject.bin")||name.endsWith(".exe")||name.endsWith(".dll")||name.endsWith(".js")||name.endsWith(".html")||name.endsWith(".svg"))budget.activeContent=true;
                    if(isUnsupportedArchive(prefix))throw rejected("OUTPUT_ARCHIVE_INVALID");
                    if(isZip(prefix)){
                        if(depth>=ARCHIVE_MAX_DEPTH)throw rejected("OUTPUT_ARCHIVE_LIMIT");
                        inspectNested(reader,prefix,depth+1,budget);
                    }else reader.drain(null);
                    reader.verify();
                }
            }
        }
        if(files==0)throw rejected("OUTPUT_ARCHIVE_INVALID");
    }

    private static void inspectNested(EntryReader reader,byte[] prefix,int depth,ArchiveBudget budget)throws IOException{
        Path directory=null,file=null;
        try{
            directory=createPrivateDirectory(budget.tempDirectory);file=createPrivateFile(directory);
            try(OutputStream out=Files.newOutputStream(file)){out.write(prefix);reader.drain(out);}
            reader.verify();inspectZipFile(file,depth,budget,Set.of(),false);
        }finally{deleteTemporary(file,directory);}
    }

    private static void deleteTemporary(Path file,Path directory){
        try{if(file!=null)Files.deleteIfExists(file);if(directory!=null)Files.deleteIfExists(directory);}
        catch(IOException e){throw new ArchiveIoException(e);}
    }

    private static Path createPrivateDirectory(Path root)throws IOException{
        try{return root==null?Files.createTempDirectory("cyf-output-archive-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))):Files.createTempDirectory(root,"cyf-output-archive-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
        catch(UnsupportedOperationException e){Path p=root==null?Files.createTempDirectory("cyf-output-archive-"):Files.createTempDirectory(root,"cyf-output-archive-");p.toFile().setReadable(false,false);p.toFile().setWritable(false,false);p.toFile().setExecutable(false,false);p.toFile().setReadable(true,true);p.toFile().setWritable(true,true);p.toFile().setExecutable(true,true);return p;}
    }

    private static Path createPrivateFile(Path directory)throws IOException{
        try{return Files.createTempFile(directory,"entry-",".zip",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
        catch(UnsupportedOperationException e){Path p=Files.createTempFile(directory,"entry-",".zip");p.toFile().setReadable(false,false);p.toFile().setWritable(false,false);p.toFile().setExecutable(false,false);p.toFile().setReadable(true,true);p.toFile().setWritable(true,true);return p;}
    }

    private static boolean isZip(byte[] bytes){return starts(bytes,0x50,0x4b,0x03,0x04)||starts(bytes,0x50,0x4b,0x05,0x06)||starts(bytes,0x50,0x4b,0x07,0x08);}
    private static boolean isUnsupportedArchive(byte[] bytes){return starts(bytes,0x1f,0x8b)||ascii(bytes,0,"BZh")||starts(bytes,0xfd,0x37,0x7a,0x58,0x5a,0x00)||starts(bytes,0x37,0x7a,0xbc,0xaf,0x27,0x1c)||ascii(bytes,0,"Rar!")||ascii(bytes,257,"ustar");}

    private static final class ArchiveBudget{
        final long memberMaxBytes,treeMaxBytes;final Path tempDirectory;long treeBytes;int entries;boolean activeContent;
        ArchiveBudget(long memberMaxBytes,long treeMaxBytes,long rootBytes,Path tempDirectory){this.memberMaxBytes=memberMaxBytes;this.treeMaxBytes=treeMaxBytes;this.treeBytes=rootBytes;this.tempDirectory=tempDirectory;if(rootBytes>treeMaxBytes)throw rejected("OUTPUT_ARCHIVE_LIMIT");}
        void add(long bytes,long memberBytes){treeBytes=Math.addExact(treeBytes,bytes);if(memberBytes>memberMaxBytes||treeBytes>treeMaxBytes)throw rejected("OUTPUT_ARCHIVE_LIMIT");}
    }

    private static final class EntryReader{
        private final InputStream input;private final ZipEntry entry;private final ArchiveBudget budget;private final CRC32 crc=new CRC32();private long count;
        EntryReader(InputStream input,ZipEntry entry,ArchiveBudget budget){this.input=input;this.entry=entry;this.budget=budget;}
        byte[] readPrefix()throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream(SNIFF_BYTES);byte[] buffer=new byte[SNIFF_BYTES];while(out.size()<SNIFF_BYTES){int n=read(buffer,0,SNIFF_BYTES-out.size());if(n<0)break;if(n>0)out.write(buffer,0,n);}return out.toByteArray();}
        void drain(OutputStream copy)throws IOException{byte[] buffer=new byte[64*1024];for(int n;(n=read(buffer,0,buffer.length))!=-1;)if(n>0&&copy!=null)copy.write(buffer,0,n);}
        private int read(byte[] buffer,int offset,int length)throws IOException{int n=input.read(buffer,offset,length);if(n>0){count=Math.addExact(count,n);budget.add(n,count);crc.update(buffer,offset,n);}return n;}
        long count(){return count;}
        void verify(){if(entry.getSize()<0||entry.getCrc()<0||entry.getSize()!=count||entry.getCrc()!=crc.getValue())throw rejected("OUTPUT_ARCHIVE_INVALID");}
    }

    static final class ArchiveIoException extends RuntimeException{ArchiveIoException(Throwable cause){super(cause);}}

    private static boolean textExtensionMatches(String ext,String mime){return switch(mime){case "text/plain"->Set.of("txt","log").contains(ext);case "text/markdown"->Set.of("md","markdown").contains(ext);case "text/csv"->ext.equals("csv");case "application/json"->ext.equals("json");default->false;};}
    private static String normalize(String mime){if(mime==null)return null;String m=mime.strip().toLowerCase(Locale.ROOT);return Set.of("text/plain","text/markdown","text/csv","application/json","image/png","image/jpeg","image/webp","application/pdf","application/zip","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation").contains(m)?m:null;}
    private static void requireText(byte[] bytes,boolean complete){int trims=complete?0:Math.min(3,bytes.length);for(int trim=0;trim<=trims;trim++){try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,bytes.length-trim));if(bytes.length>0&&bytes[0]==0)throw rejected("OUTPUT_TYPE_MISMATCH");return;}catch(CharacterCodingException ignored){}}throw rejected("OUTPUT_TEXT_ENCODING_INVALID");}
    private static boolean isZipMime(String m){return m.equals("application/zip")||m.startsWith("application/vnd.openxmlformats-officedocument.");}
    private static boolean isExecutable(byte[] b){return starts(b,0x4d,0x5a)||starts(b,0x7f,0x45,0x4c,0x46)||starts(b,0xca,0xfe,0xba,0xbe);}
    private static boolean starts(byte[] b,int...v){if(b.length<v.length)return false;for(int i=0;i<v.length;i++)if((b[i]&255)!=v[i])return false;return true;}
    private static boolean ascii(byte[] b,int at,String s){byte[] x=s.getBytes(StandardCharsets.US_ASCII);if(b.length<at+x.length)return false;for(int i=0;i<x.length;i++)if(b[at+i]!=x[i])return false;return true;}
    private static String extension(String n){int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1).toLowerCase(Locale.ROOT);}
    private static OutputUploadException rejected(String code){return new OutputUploadException(code,"Output content was rejected",415);}
    private OutputContentInspector(){}
}
