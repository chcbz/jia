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
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private SkillPackageDescriptor() { }
    static SkillManifestCodec.Manifest read(PlatformSkillPackage pkg,SkillManifestCodec codec) {
        Path temporary=null;
        try {
            temporary=Files.createTempFile("cyf-skill-descriptor-",".zip",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary,pkg.bytes());
            try(var zip=new ZipFile(temporary.toFile())) {
                var entries=zip.entries();Set<String> names=new HashSet<>();long expanded=0;
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();String name=entry.getName();
                    require(names.add(name),400,"SKILL_ARCHIVE_INVALID");
                    require(entry.getSize()>=0 && entry.getSize()<=MAX_EXPANDED_BYTES-expanded,400,"SKILL_ARCHIVE_INVALID");
                    expanded+=entry.getSize();
                }
                var instructions=zip.getEntry("SKILL.md");require(instructions!=null && !instructions.isDirectory(),400,"SKILL_ARCHIVE_INVALID");
                require(!names.contains("manifest.json/"),400,"SKILL_ARCHIVE_INVALID");
                var manifest=zip.getEntry("manifest.json");
                if(manifest==null) return null; // ordinary explanation skill, no invented execution descriptor
                require(!manifest.isDirectory(),400,"SKILL_ARCHIVE_INVALID");
                byte[] bytes;
                try(var stream=zip.getInputStream(manifest)) {
                    // The validated expanded total bounds this allocation. Read the exact entry
                    // and probe EOF separately: never add one to an int representation limit.
                    bytes=stream.readNBytes((int)manifest.getSize());
                    require(bytes.length==manifest.getSize() && stream.read()==-1,400,"SKILL_ARCHIVE_INVALID");
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
