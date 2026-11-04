package cn.jia.agent.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Concrete tool adapter: deterministic in-memory skill files -> existing immutable ZIP contract.
 * No filesystem extraction, execution, authority or marketplace state transitions. */
public final class SkillPublicationToolArchive {
    private SkillPublicationToolArchive() { }
    public record File(String path,String content,String encoding) { }
    public static byte[] encode(List<File> files) {
        require(files!=null&&!files.isEmpty()&&files.size()<=256&&files.stream().allMatch(java.util.Objects::nonNull),400,"SKILL_ARCHIVE_INVALID");
        var names=new HashSet<String>();long expanded=0;
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(var file:files.stream().sorted(Comparator.comparing(File::path,Comparator.nullsFirst(Comparator.naturalOrder()))).toList()) {
                require(file.path()!=null&&!file.path().isBlank()&&file.content()!=null
                        &&!file.path().startsWith("/")&&!file.path().contains("\\")
                        &&java.util.Arrays.stream(file.path().split("/",-1)).noneMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."))
                        &&names.add(file.path()),400,"SKILL_ARCHIVE_INVALID");
                byte[] value;
                if("utf8".equals(file.encoding()))value=file.content().getBytes(StandardCharsets.UTF_8);
                else {require("base64".equals(file.encoding()),400,"SKILL_ARCHIVE_INVALID");value=Base64.getDecoder().decode(file.content());}
                expanded+=value.length;
                require(value.length<=SkillPackageStore.MAX_PACKAGE_BYTES&&expanded<=64L*1024*1024,400,"SKILL_ARCHIVE_INVALID");
                var entry=new ZipEntry(file.path());entry.setTime(0L);zip.putNextEntry(entry);zip.write(value);zip.closeEntry();
            }
            require(names.contains("SKILL.md"),400,"SKILL_ARCHIVE_INVALID");
            zip.finish();require(bytes.size()<=SkillPackageStore.MAX_PACKAGE_BYTES,400,"SKILL_PACKAGE_INVALID");
            return bytes.toByteArray();
        } catch(IOException|IllegalArgumentException invalid) {throw new SkillMarketplaceException(400,"SKILL_ARCHIVE_INVALID");}
    }
}
