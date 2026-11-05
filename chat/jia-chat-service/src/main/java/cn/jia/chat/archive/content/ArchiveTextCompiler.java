package cn.jia.chat.archive.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Concrete UTF-8 text import tool. Never runs a shell or asks the model to reproduce the source. */
public final class ArchiveTextCompiler {
    private static final Pattern HEADING=Pattern.compile("^(?:第[零〇一二三四五六七八九十百千万两0-9]+[回章节卷](?:$|(?:\\s|[：:、]).*)|#{1,6}\\s+.+)$",Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Result(String manifestJson,String manifestFileSha256,ArchiveManifest manifest) { }
    public Result compile(String work,String edition,String title,String sourceName,byte[] source,String expectedSha256) {
        if(source==null||!ArchiveEtags.sha256(source).equals(expectedSha256))throw new ArchiveContentException("Source digest mismatch");
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();}
        catch(CharacterCodingException error){throw new ArchiveContentException("Source must be UTF-8",error);}
        if(text.indexOf(0)>=0)throw new ArchiveContentException("Source contains NUL");
        // This is the same text contract as the native skill's build-archive.mjs.
        var lines=Arrays.stream(text.replaceFirst("^\\uFEFF", "").split("\\r?\\n",-1))
                .map(ArchiveTextCompiler::trim).filter(s->!s.isEmpty()).toList();
        int first=-1;for(int i=0;i<lines.size();i++)if(heading(lines.get(i))){first=i;break;}
        ArchiveManifest.Block preface=null;var chapters=new ArrayList<ArchiveManifest.Block>();
        if(first<0)chapters.add(block("正文",lines,1));
        else {
            if(first>0)preface=block("引首",lines.subList(0,first),null);
            String current=null;var body=new ArrayList<String>();
            for(String line:lines.subList(first,lines.size())) {
                if(heading(line)) {
                    if(current!=null)chapters.add(block(current,body,chapters.size()+1));
                    current=line.replaceFirst("^#{1,6}\\s+", "");body=new ArrayList<>();
                }else body.add(line);
            }
            chapters.add(block(current,body,chapters.size()+1));
        }
        int cp=chapters.stream().mapToInt(ArchiveManifest.Block::paragraphCount).sum();
        long cb=chapters.stream().mapToLong(ArchiveManifest.Block::utf8ByteLength).sum();
        int pp=preface==null?0:preface.paragraphCount();long pb=preface==null?0:preface.utf8ByteLength();
        var manifest=new ArchiveManifest(chapters.size(),cp,cb,chapters,edition,null,preface,pp,pb,pp+cp,pb+cb,1,
                new ArchiveManifest.Source(List.of(),sourceName,null,expectedSha256,null,null,source.length),title,work);
        ObjectNode node=JSON.valueToTree(manifest);node.remove("manifestSha256");
        var loader=new ArchiveManifestLoader();node.put("manifestSha256",ArchiveEtags.sha256(loader.canonicalJsonBytes(node)));
        byte[] bytes=loader.canonicalJsonBytes(node);String digest=ArchiveEtags.sha256(bytes);
        var loaded=loader.parseAndValidate(bytes,digest);
        return new Result(new String(bytes,StandardCharsets.UTF_8),digest,loaded.manifest());
    }
    private static boolean heading(String text){return HEADING.matcher(text).matches();}
    private static String trim(String value){return value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");}
    private static ArchiveManifest.Block block(String title,List<String> texts,Integer number) {
        if(texts.isEmpty())throw new ArchiveContentException("Empty archive chapter");
        String id=number==null?"preface":"chapter-"+number;var paragraphs=new ArrayList<ArchiveManifest.Paragraph>();
        for(String text:texts){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);paragraphs.add(new ArchiveManifest.Paragraph(id+"-p-"+(paragraphs.size()+1),ArchiveEtags.sha256(bytes),text,bytes.length));}
        return new ArchiveManifest.Block(number==null?null:id,number,paragraphs.size(),paragraphs,number==null?id:null,title,
                paragraphs.stream().mapToLong(ArchiveManifest.Paragraph::utf8ByteLength).sum());
    }
}
