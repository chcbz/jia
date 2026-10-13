package cn.jia.agent.skill;

import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.ZipFile;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Bounded archive metadata projection only: never extracts files or executes SKILL.md.
 * Runtime remains responsible for full ZIP path/type isolation before activation. */
final class SkillPackageDescriptor {
    private SkillPackageDescriptor() { }
    static SkillManifestCodec.Manifest read(PlatformSkillPackage pkg,SkillManifestCodec codec) {
        Path temporary=null;
        try {
            temporary=Files.createTempFile("cyf-skill-descriptor-",".zip",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary,pkg.bytes());
            try(var zip=new ZipFile(temporary.toFile())) {
                var entries=zip.entries();Set<String> names=new HashSet<>();long expanded=0;int count=0;
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();String name=entry.getName();
                    require(++count<=256 && names.add(name),400,"SKILL_ARCHIVE_INVALID");
                    require(entry.getSize()>=0 && entry.getSize()<=SkillPackageStore.MAX_PACKAGE_BYTES,400,"SKILL_ARCHIVE_INVALID");
                    expanded+=entry.getSize();require(expanded<=64L*1024*1024,400,"SKILL_ARCHIVE_INVALID");
                }
                var instructions=zip.getEntry("SKILL.md");require(instructions!=null && !instructions.isDirectory(),400,"SKILL_ARCHIVE_INVALID");
                require(!names.contains("manifest.json/"),400,"SKILL_ARCHIVE_INVALID");
                var manifest=zip.getEntry("manifest.json");
                if(manifest==null) return null; // ordinary explanation skill, no invented execution descriptor
                require(!manifest.isDirectory(),400,"SKILL_ARCHIVE_INVALID");
                byte[] bytes;
                try(var stream=zip.getInputStream(manifest)) {
                    bytes=stream.readNBytes(SkillPackageStore.MAX_PACKAGE_BYTES+1);
                    require(bytes.length==manifest.getSize() && bytes.length<=SkillPackageStore.MAX_PACKAGE_BYTES,400,"SKILL_ARCHIVE_INVALID");
                }
                var descriptor=codec.parse(bytes,pkg.product().skillKey(),pkg.product().skillVersion());
                var document=descriptor.document();
                if("local-script".equals(document.path("runner").asText())) {
                    var script=zip.getEntry(document.path("entrypoint").asText());
                    require(script!=null && !script.isDirectory(),400,"SKILL_ENTRYPOINT_INVALID");
                }
                return descriptor;
            }
        } catch(IOException e) { throw new SkillMarketplaceException(400,"SKILL_ARCHIVE_INVALID"); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) { /* never an authorized object */ } }
    }
}
