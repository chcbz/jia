package cn.jia.chat.archive.dto;

import cn.jia.core.security.ExactContentOutput;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"paragraphId", "ordinal", "text", "utf8ByteLength", "sha256"})
public record ArchiveParagraphDTO(
        String paragraphId,
        int ordinal,
        @ExactContentOutput(reason = "published archive paragraph must be byte-faithful") String text,
        long utf8ByteLength,
        String sha256) {
}
