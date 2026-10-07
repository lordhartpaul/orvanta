package io.orvanta.forge;

import io.orvanta.core.expr.Fn;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles one expression of the Orvanta expression language into a Java expression of type Object.
 *
 * <pre>
 *   txn.amount > 0 and txn.currency in ['ZAR', 'USD']
 *   coalesce(pi.Dbtr.Nm, 'UNKNOWN')
 *   len(txn.creditor.name) <= 140 ? txn.creditor.name : left(txn.creditor.name, 140)
 * </pre>
 *
 * A name is a local variable (loop variable, let) when the caller lists it, otherwise a variable of the scope.
 */
public final class ExprCompiler {

    private enum T { NUM, STR, ID, OP, END }

    private record Token(T type, String text, int pos) {
    }

    private final String source;
    private final Map<String, String> locals;
    private final List<Token> tokens = new ArrayList<>();
    private int index;

    private ExprCompiler(String source, Map<String, String> locals) {
        this.source = source;
        this.locals = locals;
    }

    /** @param locals name of each local variable mapped to the Java expression that reads it */
    public static String compile(String source, Map<String, String> locals) {
        ExprCompiler c = new ExprCompiler(source, locals);
        c.tokenize();
        String java = c.ternary();
        if (c.peek().type != T.END) {
            throw c.error("unexpected '" + c.peek().text + "'");
        }
        return java;
    }

    // ---- lexer ----

    private void tokenize() {
        int i = 0;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (Character.isDigit(ch)) {
                int start = i;
                while (i < source.length() && (Character.isDigit(source.charAt(i))
                        || (source.charAt(i) == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1))))) {
                    i++;
                }
                tokens.add(new Token(T.NUM, source.substring(start, i), start));
            } else if (ch == '\'' || ch == '"') {
                int start = i++;
                StringBuilder sb = new StringBuilder();
                while (i < source.length() && source.charAt(i) != ch) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        char escaped = source.charAt(++i);
                        if (escaped == 'n') {
                            sb.append('\n');
                        } else if (escaped == 't') {
                            sb.append('\t');
                        } else if (escaped == '\\' || escaped == '\'' || escaped == '"') {
                            sb.append(escaped);
                        } else {
                            // not an escape of this language: keep it, so a regular expression can say \d or \s
                            sb.append('\\').append(escaped);
                        }
                        i++;
                        continue;
                    }
                    sb.append(source.charAt(i++));
                }
                if (i >= source.length()) {
                    throw new ModelException("", "unterminated text literal at position " + start + " in: " + source);
                }
                i++;
                tokens.add(new Token(T.STR, sb.toString(), start));
            } else if (Character.isLetter(ch) || ch == '_' || ch == '@' || ch == '#') {
                int start = i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(T.ID, source.substring(start, i), start));
            } else {
                String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("&&") || two.equals("||")) {
                    tokens.add(new Token(T.OP, two, i));
                    i += 2;
                } else if ("<>!+-*/()[],.?:".indexOf(ch) >= 0) {
                    tokens.add(new Token(T.OP, String.valueOf(ch), i));
                    i++;
                } else {
                    throw new ModelException("", "unexpected character '" + ch + "' at position " + i + " in: " + source);
                }
            }
        }
        tokens.add(new Token(T.END, "end of expression", source.length()));
    }

    private Token peek() {
        return tokens.get(index);
    }

    private boolean op(String text) {
        Token t = peek();
        if (t.type == T.OP && t.text.equals(text)) {
            index++;
            return true;
        }
        return false;
    }

    private boolean word(String text) {
        Token t = peek();
        if (t.type == T.ID && t.text.equals(text)) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(String text) {
        if (!op(text)) {
            throw error("expected '" + text + "' but found '" + peek().text + "'");
        }
    }

    private ModelException error(String message) {
        return new ModelException("", message + " at position " + peek().pos + " in: " + source);
    }

    // ---- parser, lowest precedence first ----

    private String ternary() {
        String cond = or();
        if (op("?")) {
            String a = ternary();
            expect(":");
            String b = ternary();
            return "(Ops.truthy(" + cond + ") ? " + a + " : " + b + ")";
        }
        return cond;
    }

    private String or() {
        String left = and();
        while (op("||") || word("or")) {
            left = "((Object) (Ops.truthy(" + left + ") || Ops.truthy(" + and() + ")))";
        }
        return left;
    }

    private String and() {
        String left = not();
        while (op("&&") || word("and")) {
            left = "((Object) (Ops.truthy(" + left + ") && Ops.truthy(" + not() + ")))";
        }
        return left;
    }

    private String not() {
        if (op("!") || word("not")) {
            return "((Object) (!Ops.truthy(" + not() + ")))";
        }
        return comparison();
    }

    private String comparison() {
        String left = additive();
        if (op("==")) {
            return "((Object) Ops.eq(" + left + ", " + additive() + "))";
        }
        if (op("!=")) {
            return "((Object) (!Ops.eq(" + left + ", " + additive() + ")))";
        }
        if (op("<=")) {
            return "((Object) Ops.le(" + left + ", " + additive() + "))";
        }
        if (op(">=")) {
            return "((Object) Ops.ge(" + left + ", " + additive() + "))";
        }
        if (op("<")) {
            return "((Object) Ops.lt(" + left + ", " + additive() + "))";
        }
        if (op(">")) {
            return "((Object) Ops.gt(" + left + ", " + additive() + "))";
        }
        if (word("in")) {
            return "((Object) Ops.in(" + left + ", " + additive() + "))";
        }
        return left;
    }

    private String additive() {
        String left = multiplicative();
        while (true) {
            if (op("+")) {
                left = "Ops.add(" + left + ", " + multiplicative() + ")";
            } else if (op("-")) {
                left = "Ops.sub(" + left + ", " + multiplicative() + ")";
            } else {
                return left;
            }
        }
    }

    private String multiplicative() {
        String left = unary();
        while (true) {
            if (op("*")) {
                left = "Ops.mul(" + left + ", " + unary() + ")";
            } else if (op("/")) {
                left = "Ops.div(" + left + ", " + unary() + ")";
            } else {
                return left;
            }
        }
    }

    private String unary() {
        if (op("-")) {
            return "Ops.neg(" + unary() + ")";
        }
        return postfix();
    }

    private String postfix() {
        String value = primary();
        while (true) {
            if (op(".")) {
                Token t = peek();
                // a field may be named by digits, like the tags of a SWIFT user header: msg.b3.121
                if (t.type != T.ID && !(t.type == T.NUM && t.text.indexOf('.') < 0)) {
                    throw error("expected a field name after '.'");
                }
                index++;
                value = "Ops.get(" + value + ", " + javaString(t.text) + ")";
            } else if (op("[")) {
                String i = ternary();
                expect("]");
                value = "Ops.idx(" + value + ", " + i + ")";
            } else {
                return value;
            }
        }
    }

    private String primary() {
        Token t = peek();
        switch (t.type) {
            case NUM -> {
                index++;
                return "((Object) new java.math.BigDecimal(\"" + t.text + "\"))";
            }
            case STR -> {
                index++;
                return "((Object) " + javaString(t.text) + ")";
            }
            case ID -> {
                index++;
                switch (t.text) {
                    case "true":
                        return "((Object) Boolean.TRUE)";
                    case "false":
                        return "((Object) Boolean.FALSE)";
                    case "null":
                        return "((Object) null)";
                    default:
                }
                if (op("(")) {
                    return call(t.text);
                }
                String local = locals.get(t.text);
                return local != null ? local : "Ops.get($s, " + javaString(t.text) + ")";
            }
            case OP -> {
                if (op("(")) {
                    String inner = ternary();
                    expect(")");
                    return inner;
                }
                if (op("[")) {
                    List<String> items = new ArrayList<>();
                    if (!op("]")) {
                        do {
                            items.add(ternary());
                        } while (op(","));
                        expect("]");
                    }
                    return "((Object) java.util.Arrays.asList(new Object[]{" + String.join(", ", items) + "}))";
                }
            }
            default -> {
            }
        }
        throw error("unexpected '" + t.text + "'");
    }

    private String call(String name) {
        List<String> args = new ArrayList<>();
        if (!op(")")) {
            do {
                args.add(ternary());
            } while (op(","));
            expect(")");
        }
        boolean nameKnown = false;
        for (Method m : Fn.class.getMethods()) {
            if (!m.getName().equals(name) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            nameKnown = true;
            int params = m.getParameterCount();
            if (m.isVarArgs() ? args.size() >= params - 1 : args.size() == params) {
                return "Fn." + name + "(" + String.join(", ", args) + ")";
            }
        }
        throw error(nameKnown
                ? "function " + name + " does not take " + args.size() + " argument(s)"
                : "unknown function '" + name + "'");
    }

    static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
