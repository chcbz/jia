import { createHash } from 'node:crypto';

const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const BOM = Uint8Array.of(0xef, 0xbb, 0xbf);
const CHAPTER = /^第([0-9]+|[零〇一二两三四五六七八九十百千万亿]+)(回|章)(?:[ \t\u3000]+.*)?$/u;
const PREFACE = /^(序言|前言)(?:[ \t\u3000]+.*)?$/u;
const EDGE_FORMATTING = /^[ \t\u3000]|[ \t\u3000]$/u;
const CHINESE_DIGITS = new Map([['零', 0], ['〇', 0], ['一', 1], ['二', 2], ['两', 2], ['三', 3], ['四', 4], ['五', 5], ['六', 6], ['七', 7], ['八', 8], ['九', 9]]);
const CHINESE_UNITS = new Map([['十', 10], ['百', 100], ['千', 1000], ['万', 10000], ['亿', 100000000]]);
const JAVA_WHITESPACE = /^[\u0009-\u000d\u001c-\u001f\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]$/u;

export class ArchiveParseError extends Error { constructor(code, message) { super(message); this.name = 'ArchiveParseError'; this.code = code; } }
function fail(code, message) { throw new ArchiveParseError(code, message); }
function exactUtf8(bytes, label) { try { return utf8.decode(bytes); } catch { fail('INVALID_UTF8', `${label} is not valid UTF-8`); } }
function hasPrefix(bytes, prefix) { return prefix.every((value, index) => bytes[index] === value); }
function range(startByte, endByte) { return { startByte, endByte }; }
function excluded(startByte, endByte, reason) { return { startByte, endByte, reason }; }

export function chapterNumber(token) {
  if (/^[0-9]+$/u.test(token)) {
    const number = Number(token);
    if (!Number.isSafeInteger(number) || number < 1 || number > 2147483647) fail('INVALID_CHAPTER_NUMBER', `invalid chapter number ${token}`);
    return number;
  }
  let total = 0; let section = 0; let current = 0;
  for (const character of token) {
    if (CHINESE_DIGITS.has(character)) { current = CHINESE_DIGITS.get(character); continue; }
    const unit = CHINESE_UNITS.get(character);
    if (unit === undefined) fail('INVALID_CHAPTER_NUMBER', `invalid chapter number ${token}`);
    if (unit < 10000) { if (current === 0) current = 1; section += current * unit; current = 0; }
    else { section += current; total += section * unit; section = 0; current = 0; }
  }
  const number = total + section + current;
  if (!Number.isSafeInteger(number) || number < 1 || number > 2147483647 || chineseNumber(number) !== token.replaceAll('两', '二')) fail('INVALID_CHAPTER_NUMBER', `invalid or ambiguous chapter number ${token}`);
  return number;
}
function groupChinese(number, omitLeadingOneTen) {
  const digits = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九']; const units = [[1000, '千'], [100, '百'], [10, '十'], [1, '']];
  let remaining = number; let output = ''; let pendingZero = false;
  for (const [unit, label] of units) {
    const digit = Math.floor(remaining / unit); remaining %= unit;
    if (digit === 0) { if (output && remaining > 0) pendingZero = true; continue; }
    if (pendingZero) { output += '零'; pendingZero = false; }
    if (!(omitLeadingOneTen && unit === 10 && digit === 1 && output === '')) output += digits[digit];
    output += label;
  }
  return output;
}
function chineseNumber(number) {
  let remaining = number; let output = '';
  const yi = Math.floor(remaining / 100000000);
  if (yi > 0) { output += groupChinese(yi, false) + '亿'; remaining %= 100000000; }
  const wan = Math.floor(remaining / 10000);
  if (wan > 0) { if (output && remaining < 10000000) output += '零'; output += groupChinese(wan, false) + '万'; remaining %= 10000; }
  if (remaining > 0) { if (output && remaining < 1000 && !output.endsWith('零')) output += '零'; output += groupChinese(remaining, output === ''); }
  return output;
}
function lines(bytes, offset) {
  const result = []; let start = offset;
  for (let index = offset; index < bytes.length; index += 1) {
    if (bytes[index] !== 0x0a) continue;
    const bodyEnd = index > start && bytes[index - 1] === 0x0d ? index - 1 : index;
    result.push({ start, end: bodyEnd, lineEnd: index + 1, delimiterStart: bodyEnd, delimiterEnd: index + 1 }); start = index + 1;
  }
  if (start < bytes.length) result.push({ start, end: bytes.length, lineEnd: bytes.length, delimiterStart: bytes.length, delimiterEnd: bytes.length });
  return result;
}
function lineText(bytes, line) {
  const segment = bytes.slice(line.start, line.end);
  if (segment.includes(0x0d)) fail('UNSUPPORTED_LINE_BREAK', 'only LF and CRLF line endings are supported');
  return exactUtf8(segment, 'source line');
}
function addDelimiter(exclusions, line) { if (line.delimiterEnd > line.delimiterStart) exclusions.push(excluded(line.delimiterStart, line.delimiterEnd, 'LINE_BREAK')); }

/** Fixed pure plain-text-v1 adapter; source prose is never interpreted as an instruction. */
export function parseText(source) {
  if (!(source instanceof Uint8Array)) fail('INVALID_INPUT', 'source must be UTF-8 bytes');
  exactUtf8(source, 'source');
  let offset = 0; const exclusions = [];
  if (source.length >= BOM.length && hasPrefix(source, BOM)) { offset = BOM.length; exclusions.push(excluded(0, BOM.length, 'UTF8_BOM')); }
  const blocks = []; let current = null; let expectedChapter = 1; let sawChapter = false;
  for (const line of lines(source, offset)) {
    const text = lineText(source, line);
    if (/^[ \t\u3000]*$/u.test(text)) { if (line.lineEnd > line.start) exclusions.push(excluded(line.start, line.lineEnd, 'BLANK_FORMATTING')); continue; }
    const chapter = CHAPTER.exec(text); const preface = PREFACE.test(text);
    if ((chapter || preface) && EDGE_FORMATTING.test(text)) fail('UNSUPPORTED_EDGE_FORMATTING', 'heading edge formatting is not accepted because server metadata is exact');
    if (!chapter && !preface && EDGE_FORMATTING.test(text) && (CHAPTER.test(text.trim()) || PREFACE.test(text.trim()))) fail('UNSUPPORTED_EDGE_FORMATTING', 'heading edge formatting is not accepted because server metadata is exact');
    if (chapter || preface) {
      if (current !== null && current.paragraphs.length === 0) fail('EMPTY_BLOCK', `block ${current.blockKey} has no paragraph`);
      if (preface) {
        if (sawChapter || blocks.some((block) => block.blockType === 'PREFACE')) fail('PREFACE_OUT_OF_ORDER', 'preface must appear once before chapters');
        current = { blockType: 'PREFACE', blockKey: 'preface', ordinal: 0, title: text, titleSourceRanges: [range(line.start, line.end)], paragraphs: [] };
      } else {
        const actual = chapterNumber(chapter[1]);
        if (actual !== expectedChapter) fail('CHAPTER_NUMBER_SEQUENCE', `expected chapter ${expectedChapter}, found ${actual}`);
        sawChapter = true; current = { blockType: 'CHAPTER', blockKey: `chapter-${actual}`, ordinal: actual, title: text, titleSourceRanges: [range(line.start, line.end)], paragraphs: [] }; expectedChapter += 1;
      }
      blocks.push(current); addDelimiter(exclusions, line); continue;
    }
    if (current === null) fail('LEADING_BODY_WITHOUT_HEADING', 'body text appears before an explicit heading');
    if (Array.from(text).some((character) => /\p{Cc}/u.test(character))) fail('UNSUPPORTED_BODY_CONTROL', 'body text contains a control character rejected by the server');
    current.paragraphs.push({ ordinal: current.paragraphs.length + 1, text, sourceRanges: [range(line.start, line.end)] }); addDelimiter(exclusions, line);
  }
  if (current !== null && current.paragraphs.length === 0) fail('EMPTY_BLOCK', `block ${current.blockKey} has no paragraph`);
  if (!sawChapter) fail('NO_CHAPTER_HEADING', 'at least one explicit chapter heading is required');
  return { blocks, excludedSourceRanges: exclusions };
}

function compareCodePoints(left, right) { const a = Array.from(left, (character) => character.codePointAt(0)); const b = Array.from(right, (character) => character.codePointAt(0)); for (let index = 0; index < Math.min(a.length, b.length); index += 1) if (a[index] !== b[index]) return a[index] - b[index]; return a.length - b.length; }
export function canonicalJson(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number') { if (!Number.isSafeInteger(value)) fail('INVALID_CANONICAL_JSON', 'only safe integer JSON numbers are supported'); return String(value); }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (typeof value === 'object') return `{${Object.keys(value).sort(compareCodePoints).map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  fail('INVALID_CANONICAL_JSON', 'unsupported JSON value');
}
export function sha256Hex(bytesOrText) { return createHash('sha256').update(bytesOrText).digest('hex'); }

const topKeys = new Set(['blocks', 'excludedSourceRanges']); const blockKeys = new Set(['blockType', 'blockKey', 'ordinal', 'title', 'titleSourceRanges', 'paragraphs']); const paragraphKeys = new Set(['ordinal', 'text', 'sourceRanges']); const rangeKeys = new Set(['startByte', 'endByte']); const exclusionKeys = new Set(['startByte', 'endByte', 'reason']);
function javaStrip(value) { const characters = Array.from(value); let start = 0; let end = characters.length; while (start < end && JAVA_WHITESPACE.test(characters[start])) start += 1; while (end > start && JAVA_WHITESPACE.test(characters[end - 1])) end -= 1; return characters.slice(start, end).join(''); }
function serverExact(value, maxBytes) { return typeof value === 'string' && javaStrip(value).length > 0 && value === javaStrip(value) && Buffer.byteLength(value, 'utf8') <= maxBytes && !Array.from(value).some((character) => /\p{Cc}/u.test(character)); }
function ownOnly(value, keys, label, findings) { if (value === null || typeof value !== 'object' || Array.isArray(value)) { findings.push(`${label} must be an object`); return false; } for (const key of Object.keys(value)) if (!keys.has(key)) findings.push(`${label} has unknown field ${key}`); return true; }
function validSpan(source, item, keys, label, findings) { if (!ownOnly(item, keys, label, findings)) return null; if (!Number.isInteger(item.startByte) || !Number.isInteger(item.endByte) || item.startByte < 0 || item.endByte <= item.startByte || item.endByte > source.length) { findings.push(`${label} has invalid byte range`); return null; } try { exactUtf8(source.slice(item.startByte, item.endByte), label); } catch (error) { findings.push(error.message); return null; } return range(item.startByte, item.endByte); }
function mappedText(source, text, sourceRanges, label, previousEnd, covered, findings) { if (typeof text !== 'string' || !Array.isArray(sourceRanges) || sourceRanges.length === 0) { findings.push(`${label} requires text and source ranges`); return previousEnd; } let last = previousEnd; const parts = []; for (const input of sourceRanges) { const span = validSpan(source, input, rangeKeys, label, findings); if (span === null) continue; if (span.startByte < last) findings.push(`${label} is out of source order`); last = span.endByte; covered.push(span); parts.push(source.slice(span.startByte, span.endByte)); } const bytes = new Uint8Array(parts.reduce((total, part) => total + part.length, 0)); let offset = 0; for (const part of parts) { bytes.set(part, offset); offset += part.length; } if (exactUtf8(bytes, label) !== text) findings.push(`${label} does not match exact source bytes`); return last; }

/** Pure local schema and exact-mapping diagnosis; server remains authoritative. */
export function validateContent(source, draft) {
  const findings = []; if (!(source instanceof Uint8Array)) return ['source must be UTF-8 bytes']; try { exactUtf8(source, 'source'); } catch (error) { return [error.message]; }
  if (!ownOnly(draft, topKeys, 'draft', findings) || !Array.isArray(draft.blocks) || !Array.isArray(draft.excludedSourceRanges)) { findings.push('draft requires blocks and excludedSourceRanges'); return findings; }
  if (draft.blocks.length === 0) findings.push('draft requires at least one block');
  const covered = []; let previousEnd = 0; let expectedChapter = 1; let prefaces = 0; const blockKeySet = new Set();
  for (const block of draft.blocks) {
    if (!ownOnly(block, blockKeys, 'block', findings)) continue;
    if (!['PREFACE', 'CHAPTER'].includes(block.blockType) || !serverExact(block.blockKey, 100) || !serverExact(block.title, 255) || !Number.isInteger(block.ordinal) || !Array.isArray(block.paragraphs)) { findings.push('block has invalid schema fields'); continue; }
    if (blockKeySet.has(block.blockKey)) findings.push(`duplicate blockKey ${block.blockKey}`);
    else blockKeySet.add(block.blockKey);
    if (block.blockType === 'PREFACE') {
      prefaces += 1;
      if (block.blockKey !== 'preface' || block.ordinal !== 0) findings.push('preface blockKey and ordinal must be preface/0');
    } else {
      if (block.blockKey !== `chapter-${block.ordinal}`) findings.push(`chapter blockKey must equal chapter-${block.ordinal}`);
      if (block.ordinal !== expectedChapter++) findings.push('chapter ordinals must be continuous from 1');
    }
    previousEnd = mappedText(source, block.title, block.titleSourceRanges, `title ${block.blockKey}`, previousEnd, covered, findings);
    if (block.paragraphs.length === 0) findings.push(`empty block ${block.blockKey}`);
    let expectedParagraph = 1;
    for (const paragraph of block.paragraphs) { if (!ownOnly(paragraph, paragraphKeys, 'paragraph', findings)) continue; if (!Number.isInteger(paragraph.ordinal) || paragraph.ordinal !== expectedParagraph++ || typeof paragraph.text !== 'string' || paragraph.text.length === 0 || Array.from(paragraph.text).some((character) => /\p{Cc}/u.test(character))) findings.push(`invalid paragraph in ${block.blockKey}`); previousEnd = mappedText(source, paragraph.text, paragraph.sourceRanges, `paragraph ${block.blockKey}/${paragraph.ordinal}`, previousEnd, covered, findings); }
  }
  if (prefaces > 1) findings.push('at most one preface is allowed'); if (expectedChapter === 1) findings.push('at least one chapter is required');
  for (const item of draft.excludedSourceRanges) { const span = validSpan(source, item, exclusionKeys, 'excluded source', findings); if (span !== null) { if (typeof item.reason !== 'string' || javaStrip(item.reason).length === 0 || item.reason.length > 255) findings.push('excluded source requires reason'); covered.push(span); } }
  covered.sort((left, right) => left.startByte - right.startByte || left.endByte - right.endByte); let cursor = 0; for (const span of covered) { if (span.startByte !== cursor) findings.push(span.startByte < cursor ? 'source ranges overlap' : 'source bytes are not fully accounted for'); cursor = Math.max(cursor, span.endByte); } if (cursor !== source.length) findings.push('source bytes are not fully accounted for'); return findings;
}