package cn.jia.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded catalog grammar, not a general SQL equivalence engine. Literal and
 * quoted-identifier contents are never case-folded. Boolean order/grouping and
 * null guards remain part of the contract. No database or resource IO. */
final class AgentMysqlCheckCanonicalizer {
    private AgentMysqlCheckCanonicalizer() {}

    static String canonicalCheck(String input) { return check(input).key(); }
    static String sql(String input) { return check(input).sql(); }
    static List<String> split(String input, String operator) {
        Node node = check(input);
        return node.operator().equals(operator)
                ? node.children().stream().map(Node::sql).toList() : List.of(node.sql());
    }

    /** Empty metadata means no generated expression, never a valid CHECK. */
    static String generatedExpression(String input) {
        if (input == null || input.isBlank()) return "";
        Parser parser = new Parser(input);
        Scalar value = parser.scalar();
        parser.finish();
        if (!value.sql().startsWith("case ")) throw malformed();
        return "generated:" + value.sql();
    }

    static String expression(String input) {
        if (input == null || input.isBlank()) return generatedExpression(input);
        Parser parser = new Parser(input);
        int first = 0;
        while (parser.tokens.get(first).text().equals("(")) first++;
        return parser.tokens.get(first).text().equals("case")
                ? generatedExpression(input) : canonicalCheck(input);
    }

    private static Node check(String input) {
        Parser parser = new Parser(input);
        Node result = parser.or();
        parser.finish();
        return result;
    }

    private static IllegalArgumentException malformed() {
        // Do not echo catalog clauses or connection/row data into startup logs.
        return new IllegalArgumentException("unsupported or malformed MySQL catalog expression");
    }

    private record Node(String operator, List<Node> children, String atom) {
        static Node atom(String value) { return new Node("predicate", List.of(), value); }
        static Node join(String operator, List<Node> children) {
            return children.size() == 1 ? children.getFirst()
                    : new Node(operator, List.copyOf(children), "");
        }
        String key() {
            if (children.isEmpty()) return "p" + atom.length() + ":" + atom;
            StringBuilder result = new StringBuilder(operator).append('(');
            for (Node child : children) {
                String key = child.key();
                result.append(key.length()).append(':').append(key);
            }
            return result.append(')').toString();
        }
        String sql() {
            if (children.isEmpty()) return atom;
            if (operator.equals("not")) return "not (" + children.getFirst().sql() + ")";
            return String.join(" " + operator + " ", children.stream()
                    .map(child -> child.children().isEmpty() ? child.sql() : "(" + child.sql() + ")")
                    .toList());
        }
    }

    private enum Kind { WORD, IDENTIFIER, STRING, NUMBER, SYMBOL, END }
    private record Token(Kind kind, String text, int start, int end) {}
    private record Scalar(String sql, boolean identifier, boolean string) {}
    private static final Set<String> RESERVED = Set.of("and", "or", "not", "is", "null",
            "between", "in", "regexp", "case", "when", "then", "else", "end", "as",
            "binary", "collate", "true", "false");

    private static List<Token> lex(String input) {
        if (input == null || input.isBlank() || input.length() > 262144) throw malformed();
        List<Token> tokens = new ArrayList<>();
        int mode = 0; // Entire expression uses either plain or escaped literal delimiters.
        int depth = 0;
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i;
            Token token;
            boolean introducer = false;
            if (letter(c) || c == '_') {
                while (++i < input.length() && (letter(input.charAt(i))
                        || Character.isDigit(input.charAt(i)) || input.charAt(i) == '_')) {}
                String word = input.substring(start, i).toLowerCase(Locale.ROOT);
                if (word.equals("_utf8mb4")) {
                    introducer = true;
                    while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
                    if (i >= input.length()) throw malformed();
                    c = input.charAt(i);
                } else {
                    if (word.startsWith("_utf8")) throw malformed();
                    token = new Token(Kind.WORD, word, start, i);
                    append(tokens, token); continue;
                }
            }
            boolean escaped = c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == '\'';
            if (c == '\'' || escaped) {
                if (escaped && !introducer) throw malformed();
                int currentMode = escaped ? 2 : 1;
                if (mode != 0 && mode != currentMode) throw malformed();
                mode = currentMode;
                i += escaped ? 2 : 1;
                StringBuilder literal = new StringBuilder("'");
                boolean closed = false;
                while (i < input.length()) {
                    char q = input.charAt(i++);
                    if (escaped) {
                        if (q == '\\' && i < input.length() && input.charAt(i) == '\'') {
                            i++; closed = true; break;
                        }
                        if (q == '\\' || q == '\'') throw malformed();
                    } else {
                        if (q == '\\') throw malformed();
                        if (q == '\'') {
                            if (i < input.length() && input.charAt(i) == '\'') {
                                literal.append("''"); i++; continue;
                            }
                            closed = true; break;
                        }
                    }
                    literal.append(q);
                }
                if (!closed) throw malformed();
                token = new Token(Kind.STRING, literal.append('\'').toString(), start, i);
            } else if (introducer) {
                throw malformed();
            } else if (c == '`') {
                i++;
                while (i < input.length() && input.charAt(i) != '`') i++;
                if (i == input.length()) throw malformed();
                String identifier = input.substring(start + 1, i++);
                if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) throw malformed();
                // Lowercase quoted names have exactly the expected spelling. Other
                // quoted spelling stays quoted, distinct from folded bare syntax.
                token = new Token(Kind.IDENTIFIER, identifier.equals(identifier.toLowerCase(Locale.ROOT))
                        && !RESERVED.contains(identifier) ? identifier : "`" + identifier + "`", start, i);
            } else if (c >= '0' && c <= '9') {
                while (++i < input.length() && input.charAt(i) >= '0' && input.charAt(i) <= '9') {}
                token = new Token(Kind.NUMBER, input.substring(start, i), start, i);
            } else if ("(),+-=<>".indexOf(c) >= 0) {
                i++;
                String symbol = String.valueOf(c);
                if ((c == '<' || c == '>') && i < input.length()
                        && (input.charAt(i) == '=' || (c == '<' && input.charAt(i) == '>'))) symbol += input.charAt(i++);
                if (c == '(' && ++depth > 128) throw malformed();
                if (c == ')' && --depth < 0) throw malformed();
                token = new Token(Kind.SYMBOL, symbol, start, i);
            } else throw malformed(); // Includes comments, backslashes, semicolons and unknown operators.
            append(tokens, token);
        }
        if (depth != 0) throw malformed();
        tokens.add(new Token(Kind.END, "", input.length(), input.length()));
        return List.copyOf(tokens);
    }

    private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static void append(List<Token> tokens, Token token) {
        if (!tokens.isEmpty()) {
            Token previous = tokens.getLast();
            if (previous.end() == token.start() && previous.kind() != Kind.SYMBOL
                    && token.kind() != Kind.SYMBOL) throw malformed();
        }
        tokens.add(token);
        if (tokens.size() > 30000) throw malformed();
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int cursor;
        private int predicateDepth;
        private int scalarDepth;
        Parser(String input) { tokens = lex(input); }
        boolean at(String value) { return tokens.get(cursor).text().equals(value); }
        boolean take(String value) { if (!at(value)) return false; cursor++; return true; }
        void require(String value) { if (!take(value)) throw malformed(); }
        void finish() { if (tokens.get(cursor).kind() != Kind.END) throw malformed(); }
        Node or() {
            List<Node> nodes = new ArrayList<>(); nodes.add(and());
            while (take("or")) nodes.add(and());
            return Node.join("or", nodes);
        }
        Node and() {
            List<Node> nodes = new ArrayList<>(); nodes.add(predicate());
            while (take("and")) nodes.add(predicate());
            return Node.join("and", nodes);
        }
        Node predicate() {
            if (++predicateDepth > 128) throw malformed();
            try { return predicateBody(); }
            finally { predicateDepth--; }
        }
        boolean scalarGroup() {
            if (!at("(")) return false;
            int depth = 0;
            for (int i = cursor; i < tokens.size() - 1; i++) {
                String token = tokens.get(i).text();
                if (token.equals("(")) depth++;
                if (token.equals(")") && --depth == 0) {
                    return Set.of("+", "-", "=", "<>", ">", ">=", "<", "<=", "is", "between", "in", "not", "regexp")
                            .contains(tokens.get(i + 1).text());
                }
            }
            return false;
        }
        Node predicateBody() {
            if (at("(") && !scalarGroup()) { cursor++; Node node = or(); require(")"); return node; }
            if (take("not")) return new Node("not", List.of(predicate()), "");
            if (at("regexp_like") && tokens.get(cursor).kind() == Kind.WORD) {
                cursor++; require("("); Scalar field = scalar();
                if (!field.identifier()) throw malformed();
                require(","); Scalar pattern = scalar();
                if (!pattern.string()) throw malformed();
                if (take(",")) {
                    Scalar match = scalar();
                    // Existing dispatch SQL explicitly requires the third 'c' arg.
                    // Keep it distinct; NEVER erase it or equate it to REGEXP.
                    if (!match.sql().equals("'c'")) throw malformed();
                    require(")");
                    return Node.atom("regexp_like(" + field.sql() + "," + pattern.sql() + ",'c')");
                }
                require(")"); return Node.atom(field.sql() + " regexp " + pattern.sql());
            }
            Scalar left = scalar();
            if (take("is")) {
                boolean not = take("not"); require("null");
                return Node.atom(left.sql() + " is " + (not ? "not " : "") + "null");
            }
            if (take("between")) {
                Scalar lower = scalar(); require("and"); Scalar upper = scalar();
                return Node.atom(left.sql() + " between " + lower.sql() + " and " + upper.sql());
            }
            boolean negatedIn = take("not");
            if (take("in")) {
                require("("); List<String> values = new ArrayList<>(); values.add(scalar().sql());
                while (take(",")) values.add(scalar().sql()); require(")");
                return Node.atom(left.sql() + (negatedIn ? " not in (" : " in (") + String.join(",", values) + ")");
            }
            if (negatedIn) throw malformed();
            if (take("regexp")) {
                Scalar pattern = scalar();
                if (!left.identifier() || !pattern.string()) throw malformed();
                return Node.atom(left.sql() + " regexp " + pattern.sql());
            }
            String operator = tokens.get(cursor).text();
            if (!Set.of("=", "<>", ">", ">=", "<", "<=").contains(operator)) throw malformed();
            cursor++;
            return Node.atom(left.sql() + " " + operator + " " + scalar().sql());
        }
        Scalar scalar() {
            if (++scalarDepth > 128) {
                scalarDepth--;
                throw malformed();
            }
            try { return scalarBody(); }
            finally { scalarDepth--; }
        }
        Scalar scalarBody() {
            Scalar value = primary();
            // Each operation has an explicit rendered tree edge. primary() can
            // remove redundant scalar parentheses without erasing operation
            // grouping: a+(b+c) and (a+b)+c therefore remain distinct.
            while (at("+") || at("-")) {
                String operator = tokens.get(cursor++).text();
                value = new Scalar("(" + value.sql() + " " + operator + " " + primary().sql() + ")", false, false);
            }
            return value;
        }
        Scalar primary() {
            if (take("(")) { Scalar value = scalar(); require(")"); return value; }
            if (take("case")) {
                require("when"); Node condition = or(); require("then"); Scalar yes = scalar();
                require("else"); Scalar no = scalar(); require("end");
                return new Scalar("case when " + condition.sql() + " then " + yes.sql()
                        + " else " + no.sql() + " end", false, false);
            }
            Token token = tokens.get(cursor++);
            if (token.kind() == Kind.STRING) return new Scalar(token.text(), false, true);
            if (token.kind() == Kind.NUMBER || token.kind() == Kind.WORD && token.text().equals("null"))
                return new Scalar(token.text(), false, false);
            if (token.kind() != Kind.WORD && token.kind() != Kind.IDENTIFIER) throw malformed();
            String word = token.text();
            if (RESERVED.contains(word)) throw malformed();
            if (!take("(")) return new Scalar(word, true, false);
            // Quoted function names are not unquoted function tokens/aliases.
            if (token.kind() != Kind.WORD) throw malformed();
            if (word.equals("cast")) {
                Scalar field = scalar(); if (!field.identifier()) throw malformed();
                require("as"); require("binary"); require(")");
                return new Scalar("cast(" + field.sql() + " as binary)", false, false);
            }
            if (!Set.of("length", "octet_length", "trim", "least", "concat", "lower", "hex", "sha2", "lpad").contains(word)) throw malformed();
            List<Scalar> args = new ArrayList<>(); args.add(scalar());
            while (take(",")) args.add(scalar()); require(")");
            int count = args.size();
            int arity = switch (word) {
                case "length", "octet_length", "trim", "lower", "hex" -> 1;
                case "least", "sha2" -> 2;
                case "lpad" -> 3;
                default -> count >= 2 ? count : -1;
            };
            if (count != arity) throw malformed();
            if ((word.equals("length") || word.equals("octet_length") || word.equals("trim")) && !args.getFirst().identifier()) throw malformed();
            if (word.equals("length")) word = "octet_length";
            return new Scalar(word + "(" + String.join(",", args.stream().map(Scalar::sql).toList()) + ")", false, false);
        }
    }
}
