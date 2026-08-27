package cn.jia.build.security;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A fail-closed parser for the ordinary YAML subset needed by configuration security classification. */
final class BoundedYamlParser {
    private static final int MAX_BYTES = 1 << 20;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_LINES = 10_000;
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>](?:[+-]?[1-9]?|[1-9][+-]?)");

    enum Kind {
        MAPPING,
        SEQUENCE,
        SCALAR,
        NULL,
        BLOCK_SCALAR
    }

    static final class Node {
        final Kind kind;
        final String scalar;
        final List<Pair> pairs;
        final List<Node> items;

        private Node(Kind kind, String scalar, List<Pair> pairs, List<Node> items) {
            this.kind = kind;
            this.scalar = scalar;
            this.pairs = pairs == null ? List.of() : List.copyOf(pairs);
            this.items = items == null ? List.of() : List.copyOf(items);
        }

        static Node scalar(String value) {
            return new Node(Kind.SCALAR, value, null, null);
        }

        static Node plainScalar(String value) {
            return isExplicitNull(value) ? nullNode() : scalar(value);
        }

        static Node nullNode() {
            return new Node(Kind.NULL, null, null, null);
        }

        static Node blockScalar() {
            return new Node(Kind.BLOCK_SCALAR, null, null, null);
        }

        static Node mapping(List<Pair> pairs) {
            return new Node(Kind.MAPPING, null, pairs, null);
        }

        static Node sequence(List<Node> items) {
            return new Node(Kind.SEQUENCE, null, null, items);
        }

        private static boolean isExplicitNull(String value) {
            return value.equals("~") || value.toLowerCase(Locale.ROOT).equals("null");
        }
    }

    record Pair(String key, Node value) { }

    private final String[] lines;
    private int nodes;

    private BoundedYamlParser(String text) throws PublicArtifactVerifier.VerificationException {
        this.lines = text.split("\\r\\n|\\n|\\r", -1);
        if (lines.length > MAX_LINES) {
            throw failure();
        }
    }

    static List<Node> parse(byte[] bytes) throws PublicArtifactVerifier.VerificationException {
        if (bytes.length > MAX_BYTES) {
            throw failure();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            throw failure();
        }
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        return new BoundedYamlParser(text).parseDocuments();
    }

    private List<Node> parseDocuments() throws PublicArtifactVerifier.VerificationException {
        List<Node> documents = new ArrayList<>();
        int index = nextSignificant(0);
        while (index < lines.length) {
            Line line = line(index);
            if (line.indent != 0) {
                throw failure();
            }
            if (line.content.startsWith("%")) {
                throw failure();
            }
            if (line.content.equals("...")) {
                index = nextSignificant(index + 1);
                continue;
            }
            if (line.content.equals("---")) {
                index = nextSignificant(index + 1);
                if (index >= lines.length) {
                    documents.add(count(Node.nullNode(), 0));
                    break;
                }
                line = line(index);
                if (line.content.equals("---") || line.content.equals("...")) {
                    documents.add(count(Node.nullNode(), 0));
                    continue;
                }
            }
            if (line.indent != 0 || isDocumentMarker(line)) {
                throw failure();
            }
            Parsed parsed = parseBlockNode(index, line.indent, 0);
            documents.add(parsed.node);
            index = nextSignificant(parsed.nextIndex);
            if (index < lines.length) {
                Line next = line(index);
                if (next.indent != 0 || (!next.content.equals("---") && !next.content.equals("..."))) {
                    throw failure();
                }
                if (next.content.equals("...")) {
                    index = nextSignificant(index + 1);
                    if (index < lines.length && !line(index).content.equals("---")) {
                        throw failure();
                    }
                    continue;
                }
            }
        }
        return List.copyOf(documents);
    }

    private Parsed parseBlockNode(int index, int indent, int depth)
            throws PublicArtifactVerifier.VerificationException {
        requireDepth(depth);
        Line current = line(index);
        if (current.indent != indent || isDocumentMarker(current)) {
            throw failure();
        }
        if (isSequenceItem(current.content)) {
            return parseBlockSequence(index, indent, depth + 1);
        }
        if (current.content.startsWith("{") || current.content.startsWith("[")) {
            InlineCollected inline = collectInline(current.content, index);
            return new Parsed(new FlowParser(inline.text, depth + 1).parseSingleNode(), inline.nextIndex);
        }
        if (findMappingColon(current.content) >= 0) {
            return parseBlockMapping(index, indent, depth + 1);
        }
        rejectUnsupportedToken(current.content);
        Node scalar = count(parseScalarToken(current.content), depth);
        int next = nextSignificant(index + 1);
        if (next < lines.length && !isDocumentMarker(line(next)) && line(next).indent > indent) {
            throw failure();
        }
        return new Parsed(scalar, index + 1);
    }

    private Parsed parseBlockMapping(int index, int indent, int depth)
            throws PublicArtifactVerifier.VerificationException {
        requireDepth(depth);
        List<Pair> pairs = new ArrayList<>();
        int cursor = index;
        while (cursor < lines.length) {
            int significant = nextSignificant(cursor);
            if (significant >= lines.length) {
                cursor = significant;
                break;
            }
            Line current = line(significant);
            if (isDocumentMarker(current) || current.indent < indent) {
                cursor = significant;
                break;
            }
            if (current.indent != indent || isSequenceItem(current.content)) {
                cursor = significant;
                break;
            }
            int colon = findMappingColon(current.content);
            if (colon < 0) {
                break;
            }
            PairParsed pair = parseBlockPair(current.content, significant, indent, depth);
            pairs.add(pair.pair);
            cursor = pair.nextIndex;
        }
        if (pairs.isEmpty()) {
            throw failure();
        }
        return new Parsed(count(Node.mapping(pairs), depth), cursor);
    }

    private PairParsed parseBlockPair(String content, int lineIndex, int indent, int depth)
            throws PublicArtifactVerifier.VerificationException {
        int colon = findMappingColon(content);
        if (colon < 0) {
            throw failure();
        }
        String rawKey = content.substring(0, colon).trim();
        if (rawKey.startsWith("?") || rawKey.isEmpty()) {
            throw failure();
        }
        String key = decodeScalar(rawKey, true);
        if (key.equals("<<")) {
            throw failure();
        }
        String valueText = content.substring(colon + 1).trim();
        Node value;
        int nextIndex = lineIndex + 1;
        if (valueText.isEmpty()) {
            int childIndex = nextSignificant(nextIndex);
            if (childIndex < lines.length) {
                Line child = line(childIndex);
                if (!isDocumentMarker(child) && child.indent > indent) {
                    Parsed parsed = parseBlockNode(childIndex, child.indent, depth + 1);
                    value = parsed.node;
                    nextIndex = parsed.nextIndex;
                } else {
                    value = count(Node.nullNode(), depth + 1);
                }
            } else {
                value = count(Node.nullNode(), depth + 1);
            }
        } else if (BLOCK_SCALAR.matcher(valueText).matches()) {
            BlockScalarParsed block = consumeBlockScalar(lineIndex + 1, indent, valueText);
            value = count(Node.blockScalar(), depth + 1);
            nextIndex = block.nextIndex;
        } else {
            InlineCollected inline = collectInline(valueText, lineIndex);
            value = new FlowParser(inline.text, depth + 1).parseSingleNode();
            nextIndex = inline.nextIndex;
            int childIndex = nextSignificant(nextIndex);
            if (childIndex < lines.length && !isDocumentMarker(line(childIndex))
                    && line(childIndex).indent > indent) {
                throw failure();
            }
        }
        return new PairParsed(new Pair(key, value), nextIndex);
    }

    private Parsed parseBlockSequence(int index, int indent, int depth)
            throws PublicArtifactVerifier.VerificationException {
        requireDepth(depth);
        List<Node> items = new ArrayList<>();
        int cursor = index;
        while (cursor < lines.length) {
            int significant = nextSignificant(cursor);
            if (significant >= lines.length) {
                cursor = significant;
                break;
            }
            Line current = line(significant);
            if (isDocumentMarker(current) || current.indent < indent) {
                cursor = significant;
                break;
            }
            if (current.indent != indent || !isSequenceItem(current.content)) {
                cursor = significant;
                break;
            }
            String itemText = current.content.length() == 1 ? "" : current.content.substring(1).trim();
            if (itemText.isEmpty()) {
                int childIndex = nextSignificant(significant + 1);
                if (childIndex < lines.length && !isDocumentMarker(line(childIndex))
                        && line(childIndex).indent > indent) {
                    Parsed parsed = parseBlockNode(childIndex, line(childIndex).indent, depth + 1);
                    items.add(parsed.node);
                    cursor = parsed.nextIndex;
                } else {
                    items.add(count(Node.nullNode(), depth + 1));
                    cursor = significant + 1;
                }
            } else if (findMappingColon(itemText) >= 0) {
                Parsed parsed = parseCompactSequenceMapping(itemText, significant, indent, depth + 1);
                items.add(parsed.node);
                cursor = parsed.nextIndex;
            } else {
                rejectUnsupportedToken(itemText);
                InlineCollected inline = collectInline(itemText, significant);
                Node node = new FlowParser(inline.text, depth + 1).parseSingleNode();
                items.add(node);
                cursor = inline.nextIndex;
                int childIndex = nextSignificant(cursor);
                if (childIndex < lines.length && !isDocumentMarker(line(childIndex))
                        && line(childIndex).indent > indent) {
                    throw failure();
                }
            }
        }
        return new Parsed(count(Node.sequence(items), depth), cursor);
    }

    private Parsed parseCompactSequenceMapping(String firstPair, int lineIndex, int sequenceIndent, int depth)
            throws PublicArtifactVerifier.VerificationException {
        int mappingIndent = sequenceIndent + 2;
        List<Pair> pairs = new ArrayList<>();
        PairParsed first = parseBlockPair(firstPair, lineIndex, mappingIndent, depth);
        pairs.add(first.pair);
        int cursor = first.nextIndex;
        while (cursor < lines.length) {
            int significant = nextSignificant(cursor);
            if (significant >= lines.length) {
                cursor = significant;
                break;
            }
            Line next = line(significant);
            if (isDocumentMarker(next) || next.indent <= sequenceIndent) {
                cursor = significant;
                break;
            }
            if (next.indent != mappingIndent || isSequenceItem(next.content)
                    || findMappingColon(next.content) < 0) {
                throw failure();
            }
            PairParsed pair = parseBlockPair(next.content, significant, mappingIndent, depth);
            pairs.add(pair.pair);
            cursor = pair.nextIndex;
        }
        return new Parsed(count(Node.mapping(pairs), depth), cursor);
    }

    private BlockScalarParsed consumeBlockScalar(int start, int parentIndent, String indicator)
            throws PublicArtifactVerifier.VerificationException {
        int explicitIndent = 0;
        for (int i = 1; i < indicator.length(); i++) {
            if (Character.isDigit(indicator.charAt(i))) {
                explicitIndent = indicator.charAt(i) - '0';
            }
        }
        int contentIndent = explicitIndent == 0 ? -1 : parentIndent + explicitIndent;
        int cursor = start;
        while (cursor < lines.length) {
            String raw = lines[cursor];
            if (raw.isBlank()) {
                cursor++;
                continue;
            }
            int indent = indentation(raw);
            if (indent <= parentIndent) {
                break;
            }
            if (contentIndent < 0) {
                contentIndent = indent;
            }
            if (indent < contentIndent) {
                throw failure();
            }
            cursor++;
        }
        return new BlockScalarParsed(cursor);
    }

    private InlineCollected collectInline(String first, int lineIndex)
            throws PublicArtifactVerifier.VerificationException {
        rejectUnsupportedToken(first);
        Balance balance = balance(first, new Balance());
        if (balance.quote == 0 && balance.delimiters.isEmpty()) {
            return new InlineCollected(first, lineIndex + 1);
        }
        StringBuilder combined = new StringBuilder(first);
        int cursor = lineIndex + 1;
        while (cursor < lines.length && (balance.quote != 0 || !balance.delimiters.isEmpty())) {
            Line continuation = line(cursor);
            if (isDocumentMarker(continuation)) {
                throw failure();
            }
            rejectUnsupportedToken(continuation.content);
            combined.append('\n').append(continuation.content);
            balance = balance(continuation.content, balance);
            cursor++;
        }
        if (balance.quote != 0 || !balance.delimiters.isEmpty()) {
            throw failure();
        }
        return new InlineCollected(combined.toString(), cursor);
    }

    private static Balance balance(String text, Balance state)
            throws PublicArtifactVerifier.VerificationException {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (state.quote == '"' && ch == '\\') {
                index++;
                if (index >= text.length()) {
                    throw failure();
                }
                continue;
            }
            if (state.quote == '\'' && ch == '\'' && index + 1 < text.length()
                    && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (state.quote != 0) {
                if (ch == state.quote) {
                    state.quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                state.quote = ch;
            } else if (ch == '{' || ch == '[') {
                state.delimiters.add(ch);
                if (state.delimiters.size() > MAX_DEPTH) {
                    throw failure();
                }
            } else if (ch == '}' || ch == ']') {
                char expected = ch == '}' ? '{' : '[';
                if (state.delimiters.isEmpty()
                        || state.delimiters.remove(state.delimiters.size() - 1) != expected) {
                    throw failure();
                }
            }
        }
        return state;
    }

    private Line line(int index) throws PublicArtifactVerifier.VerificationException {
        String raw = lines[index];
        int indent = indentation(raw);
        String content = stripComment(raw.substring(indent)).trim();
        if (content.startsWith("%")) {
            throw failure();
        }
        return new Line(index, indent, content);
    }

    private int nextSignificant(int start) throws PublicArtifactVerifier.VerificationException {
        int index = start;
        while (index < lines.length) {
            Line candidate = line(index);
            if (!candidate.content.isEmpty()) {
                return index;
            }
            index++;
        }
        return index;
    }

    private static int indentation(String raw) throws PublicArtifactVerifier.VerificationException {
        int index = 0;
        while (index < raw.length() && raw.charAt(index) == ' ') {
            index++;
        }
        if (index < raw.length() && raw.charAt(index) == '\t') {
            throw failure();
        }
        return index;
    }

    private static String stripComment(String text) throws PublicArtifactVerifier.VerificationException {
        char quote = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quote == '"' && ch == '\\') {
                index++;
                if (index >= text.length()) {
                    throw failure();
                }
                continue;
            }
            if (quote == '\'' && ch == '\'' && index + 1 < text.length()
                    && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '\t') {
                throw failure();
            } else if (ch == '#' && (index == 0 || Character.isWhitespace(text.charAt(index - 1)))) {
                return text.substring(0, index);
            }
        }
        if (quote != 0) {
            throw failure();
        }
        return text;
    }

    private static void rejectUnsupportedToken(String text) throws PublicArtifactVerifier.VerificationException {
        char quote = 0;
        boolean tokenStart = true;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quote == '"' && ch == '\\') {
                index++;
                if (index >= text.length()) {
                    throw failure();
                }
                tokenStart = false;
                continue;
            }
            if (quote == '\'' && ch == '\'' && index + 1 < text.length()
                    && text.charAt(index + 1) == '\'') {
                index++;
                tokenStart = false;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                tokenStart = false;
                continue;
            }
            if (ch == '\t') {
                throw failure();
            }
            if (tokenStart && (ch == '&' || ch == '*' || ch == '!' || ch == '?')) {
                throw failure();
            }
            tokenStart = Character.isWhitespace(ch) || ch == ':' || ch == ',' || ch == '[' || ch == '{' || ch == '-';
        }
        if (quote != 0) {
            throw failure();
        }
    }

    private static boolean isSequenceItem(String content) {
        return content.equals("-") || content.startsWith("- ");
    }

    private static boolean isDocumentMarker(Line line) {
        return line.indent == 0 && (line.content.equals("---") || line.content.equals("..."));
    }

    private static int findMappingColon(String text) throws PublicArtifactVerifier.VerificationException {
        char quote = 0;
        int flowDepth = 0;
        int placeholderDepth = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quote == '"' && ch == '\\') {
                index++;
                if (index >= text.length()) {
                    throw failure();
                }
                continue;
            }
            if (quote == '\'' && ch == '\'' && index + 1 < text.length()
                    && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '$' && index + 1 < text.length() && text.charAt(index + 1) == '{') {
                placeholderDepth++;
                index++;
            } else if (ch == '}' && placeholderDepth > 0) {
                placeholderDepth--;
            } else if (placeholderDepth == 0 && (ch == '{' || ch == '[')) {
                flowDepth++;
            } else if (placeholderDepth == 0 && (ch == '}' || ch == ']')) {
                flowDepth--;
                if (flowDepth < 0) {
                    throw failure();
                }
            } else if (placeholderDepth == 0 && flowDepth == 0 && ch == ':'
                    && (index + 1 == text.length() || Character.isWhitespace(text.charAt(index + 1))
                    || text.charAt(index + 1) == '{' || text.charAt(index + 1) == '[')) {
                return index;
            }
        }
        if (quote != 0 || flowDepth != 0 || placeholderDepth != 0) {
            throw failure();
        }
        return -1;
    }

    private static Node parseScalarToken(String token) throws PublicArtifactVerifier.VerificationException {
        String trimmed = token.trim();
        String decoded = decodeScalar(trimmed, false);
        return trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"'
                ? Node.scalar(decoded)
                : Node.plainScalar(decoded);
    }

    private static String decodeScalar(String token, boolean key)
            throws PublicArtifactVerifier.VerificationException {
        String trimmed = token.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw failure();
        }
        rejectUnsupportedToken(trimmed);
        if (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"') {
            ScalarParsed parsed = decodeQuoted(trimmed, 0);
            if (parsed.nextIndex != trimmed.length()) {
                throw failure();
            }
            return parsed.value;
        }
        if (key && (trimmed.indexOf('{') >= 0 || trimmed.indexOf('[') >= 0
                || trimmed.indexOf(',') >= 0)) {
            throw failure();
        }
        return trimmed;
    }

    private static ScalarParsed decodeQuoted(String text, int start)
            throws PublicArtifactVerifier.VerificationException {
        char quote = text.charAt(start);
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < text.length()) {
            char ch = text.charAt(index++);
            if (ch == '\n' || ch == '\r') {
                throw failure();
            }
            if (quote == '\'') {
                if (ch == '\'') {
                    if (index < text.length() && text.charAt(index) == '\'') {
                        value.append('\'');
                        index++;
                        continue;
                    }
                    return new ScalarParsed(value.toString(), index);
                }
                value.append(ch);
                continue;
            }
            if (ch == '"') {
                return new ScalarParsed(value.toString(), index);
            }
            if (ch != '\\') {
                value.append(ch);
                continue;
            }
            if (index >= text.length()) {
                throw failure();
            }
            char escaped = text.charAt(index++);
            int simple = switch (escaped) {
                case '0' -> 0x0000;
                case 'a' -> 0x0007;
                case 'b' -> 0x0008;
                case 't' -> 0x0009;
                case 'n' -> 0x000a;
                case 'v' -> 0x000b;
                case 'f' -> 0x000c;
                case 'r' -> 0x000d;
                case 'e' -> 0x001b;
                case ' ' -> 0x0020;
                case '"' -> 0x0022;
                case '/' -> 0x002f;
                case '\\' -> 0x005c;
                case 'N' -> 0x0085;
                case '_' -> 0x00a0;
                case 'L' -> 0x2028;
                case 'P' -> 0x2029;
                default -> -1;
            };
            if (simple >= 0) {
                value.appendCodePoint(simple);
                continue;
            }
            int digits = escaped == 'x' ? 2 : escaped == 'u' ? 4 : escaped == 'U' ? 8 : 0;
            if (digits == 0 || index + digits > text.length()) {
                throw failure();
            }
            long codePoint = 0;
            for (int digit = 0; digit < digits; digit++) {
                int valueDigit = Character.digit(text.charAt(index++), 16);
                if (valueDigit < 0) {
                    throw failure();
                }
                codePoint = (codePoint << 4) | valueDigit;
            }
            if (codePoint > Character.MAX_CODE_POINT
                    || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw failure();
            }
            value.appendCodePoint((int) codePoint);
        }
        throw failure();
    }

    private Node count(Node node, int depth) throws PublicArtifactVerifier.VerificationException {
        requireDepth(depth);
        nodes++;
        if (nodes > MAX_NODES) {
            throw failure();
        }
        return node;
    }

    private static void requireDepth(int depth) throws PublicArtifactVerifier.VerificationException {
        if (depth > MAX_DEPTH) {
            throw failure();
        }
    }

    private static PublicArtifactVerifier.VerificationException failure() {
        return new PublicArtifactVerifier.VerificationException(
                PublicArtifactVerifier.FailureCode.CONFIGURATION_PARSE_ERROR);
    }

    private final class FlowParser {
        private final String text;
        private final int initialDepth;
        private int index;

        private FlowParser(String text, int initialDepth) {
            this.text = text;
            this.initialDepth = initialDepth;
        }

        Node parseSingleNode() throws PublicArtifactVerifier.VerificationException {
            skipWhitespace();
            Node value = parseNode(initialDepth);
            skipWhitespace();
            if (index != text.length()) {
                throw failure();
            }
            return value;
        }

        private Node parseNode(int depth) throws PublicArtifactVerifier.VerificationException {
            requireDepth(depth);
            skipWhitespace();
            if (index >= text.length()) {
                throw failure();
            }
            char ch = text.charAt(index);
            if (ch == '{') {
                return parseMapping(depth + 1);
            }
            if (ch == '[') {
                return parseSequence(depth + 1);
            }
            boolean quoted = text.charAt(index) == '\'' || text.charAt(index) == '"';
            String scalar = parseScalarUntil(",]}");
            return count(quoted ? Node.scalar(scalar) : Node.plainScalar(scalar), depth);
        }

        private Node parseMapping(int depth) throws PublicArtifactVerifier.VerificationException {
            index++;
            List<Pair> pairs = new ArrayList<>();
            skipWhitespace();
            if (take('}')) {
                return count(Node.mapping(pairs), depth);
            }
            while (true) {
                skipWhitespace();
                String key = parseKey();
                if (key.equals("<<")) {
                    throw failure();
                }
                skipWhitespace();
                require(':');
                skipWhitespace();
                Node value;
                if (peek(',') || peek('}')) {
                    value = count(Node.nullNode(), depth + 1);
                } else {
                    value = parseNode(depth + 1);
                }
                pairs.add(new Pair(key, value));
                skipWhitespace();
                if (take('}')) {
                    break;
                }
                require(',');
                skipWhitespace();
                if (peek('}')) {
                    throw failure();
                }
            }
            return count(Node.mapping(pairs), depth);
        }

        private Node parseSequence(int depth) throws PublicArtifactVerifier.VerificationException {
            index++;
            List<Node> items = new ArrayList<>();
            skipWhitespace();
            if (take(']')) {
                return count(Node.sequence(items), depth);
            }
            while (true) {
                skipWhitespace();
                items.add(parseSequenceItem(depth + 1));
                skipWhitespace();
                if (take(']')) {
                    break;
                }
                require(',');
                skipWhitespace();
                if (peek(']')) {
                    throw failure();
                }
            }
            return count(Node.sequence(items), depth);
        }

        private Node parseSequenceItem(int depth) throws PublicArtifactVerifier.VerificationException {
            if (peek('{') || peek('[')) {
                return parseNode(depth);
            }
            int saved = index;
            String possibleKey;
            try {
                possibleKey = parseKeyCandidate();
            } catch (PublicArtifactVerifier.VerificationException ignored) {
                index = saved;
                return parseNode(depth);
            }
            skipWhitespace();
            if (!take(':')) {
                index = saved;
                return parseNode(depth);
            }
            skipWhitespace();
            Node value = peek(',') || peek(']')
                    ? count(Node.nullNode(), depth + 1)
                    : parseNode(depth + 1);
            return count(Node.mapping(List.of(new Pair(possibleKey, value))), depth);
        }

        private String parseKey() throws PublicArtifactVerifier.VerificationException {
            String key = parseKeyCandidate();
            skipWhitespace();
            if (!peek(':')) {
                throw failure();
            }
            return key;
        }

        private String parseKeyCandidate() throws PublicArtifactVerifier.VerificationException {
            skipWhitespace();
            if (index >= text.length()) {
                throw failure();
            }
            if (text.charAt(index) == '\'' || text.charAt(index) == '"') {
                ScalarParsed parsed = decodeQuoted(text, index);
                index = parsed.nextIndex;
                return parsed.value;
            }
            int start = index;
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == ':') {
                    break;
                }
                if (ch == ',' || ch == '}' || ch == ']' || ch == '{' || ch == '[') {
                    throw failure();
                }
                index++;
            }
            return decodeScalar(text.substring(start, index), true);
        }

        private String parseScalarUntil(String delimiters)
                throws PublicArtifactVerifier.VerificationException {
            skipWhitespace();
            if (index >= text.length()) {
                throw failure();
            }
            if (text.charAt(index) == '\'' || text.charAt(index) == '"') {
                ScalarParsed parsed = decodeQuoted(text, index);
                index = parsed.nextIndex;
                return parsed.value;
            }
            int start = index;
            int placeholderDepth = 0;
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == '$' && index + 1 < text.length() && text.charAt(index + 1) == '{') {
                    placeholderDepth++;
                    index += 2;
                    continue;
                }
                if (ch == '}' && placeholderDepth > 0) {
                    placeholderDepth--;
                    index++;
                    continue;
                }
                if (placeholderDepth == 0 && delimiters.indexOf(ch) >= 0) {
                    break;
                }
                index++;
            }
            if (placeholderDepth != 0) {
                throw failure();
            }
            return decodeScalar(text.substring(start, index), false);
        }

        private void skipWhitespace() throws PublicArtifactVerifier.VerificationException {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                if (text.charAt(index) == '\n' || text.charAt(index) == '\r') {
                    // Flow collections may span lines; quoted/plain scalar folding may not.
                }
                index++;
            }
        }

        private void require(char expected) throws PublicArtifactVerifier.VerificationException {
            if (!take(expected)) {
                throw failure();
            }
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }
    }

    private record Parsed(Node node, int nextIndex) { }

    private record PairParsed(Pair pair, int nextIndex) { }

    private record BlockScalarParsed(int nextIndex) { }

    private record InlineCollected(String text, int nextIndex) { }

    private record ScalarParsed(String value, int nextIndex) { }

    private record Line(int index, int indent, String content) { }

    private static final class Balance {
        private char quote;
        private final List<Character> delimiters = new ArrayList<>();
    }
}
