import { ArchiveParseError, canonicalJson, parseText, sha256Hex, validateContent } from './parse-text.mjs';

/** Pure local diagnosis for bytes provided by the controlled bridge; it performs no filesystem, shell, network, or model action. */
export function diagnoseBytes(bytes) {
  try {
    const draft = parseText(bytes);
    const findings = validateContent(bytes, draft);
    if (findings.length > 0) return { ok: false, code: 'LOCAL_MAPPING_INVALID', findings };
    return { ok: true, sourceSha256: sha256Hex(bytes), canonicalJsonSha256: sha256Hex(canonicalJson(draft)), draft };
  } catch (error) {
    if (error instanceof ArchiveParseError) return { ok: false, code: error.code, message: error.message };
    throw error;
  }
}