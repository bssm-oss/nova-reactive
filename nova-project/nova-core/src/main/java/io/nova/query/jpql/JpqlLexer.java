package io.nova.query.jpql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JPQL 문자열을 {@link JpqlToken} 스트림으로 토큰화하는 손으로 작성한 렉서다. 외부 파서 라이브러리
 * (ANSI/ANTLR 등)를 쓰지 않는다 — nova-core는 Reactor/R2DBC SPI 외 production 의존을 추가하지 않는다.
 * <p>
 * 인식하는 렉시컬 요소:
 * <ul>
 *   <li>식별자와 예약어(대소문자 무시). 예약어 목록에 없으면 {@link TokenType#IDENTIFIER}.</li>
 *   <li>정수/소수 리터럴, 지수 표기는 미지원(있으면 fail-fast).</li>
 *   <li>작은따옴표 문자열 리터럴. {@code ''}는 escape된 단일 따옴표로 처리.</li>
 *   <li>{@code :name} named 파라미터, {@code ?1} positional 파라미터.</li>
 *   <li>연산자/구두점: {@code = <> != < > <= >= + - * / ( ) , .}</li>
 * </ul>
 */
public final class JpqlLexer {

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "DISTINCT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC",
            "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "FETCH", "ON", "AS",
            "AND", "OR", "NOT", "LIKE", "IN", "BETWEEN", "IS", "NULL", "EXISTS", "MEMBER", "OF",
            "UPDATE", "SET", "DELETE", "NEW",
            "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE",
            "TREAT", "TYPE", "ESCAPE", "EMPTY");

    private final String source;
    private int pos;

    public JpqlLexer(String source) {
        if (source == null) {
            throw new JpqlSyntaxException("JPQL source must not be null");
        }
        this.source = source;
        this.pos = 0;
    }

    /**
     * 전체 소스를 토큰화해 마지막에 {@link TokenType#EOF} 토큰을 붙여 반환한다.
     */
    public List<JpqlToken> tokenize() {
        List<JpqlToken> tokens = new ArrayList<>();
        JpqlToken token;
        do {
            token = next();
            tokens.add(token);
        } while (token.type() != TokenType.EOF);
        return tokens;
    }

    private JpqlToken next() {
        skipWhitespace();
        if (pos >= source.length()) {
            return new JpqlToken(TokenType.EOF, "", pos);
        }
        int start = pos;
        char c = source.charAt(pos);

        if (c == '\'') {
            return stringLiteral(start);
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < source.length() && Character.isDigit(source.charAt(pos + 1)))) {
            return numberLiteral(start);
        }
        if (c == ':') {
            return namedParameter(start);
        }
        if (c == '?') {
            return positionalParameter(start);
        }
        if (isIdentifierStart(c)) {
            return word(start);
        }
        return operator(start);
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    private JpqlToken stringLiteral(int start) {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\'') {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '\'') {
                    sb.append('\'');
                    pos += 2;
                    continue;
                }
                pos++; // closing quote
                return new JpqlToken(TokenType.STRING, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new JpqlSyntaxException("Unterminated string literal starting at position " + start);
    }

    private JpqlToken numberLiteral(int start) {
        boolean seenDot = false;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' && !seenDot) {
                seenDot = true;
                pos++;
            } else {
                break;
            }
        }
        String text = source.substring(start, pos);
        // 지수 표기(1e5)나 접미사(L/D/F)는 v1 미지원 — 뒤에 식별자 문자가 붙으면 fail-fast.
        if (pos < source.length() && isIdentifierPart(source.charAt(pos))) {
            throw new JpqlSyntaxException(
                    "Malformed numeric literal near position " + start + " ('" + text
                            + source.charAt(pos) + "...'): exponent/suffix notation is not supported");
        }
        return new JpqlToken(TokenType.NUMBER, text, start);
    }

    private JpqlToken namedParameter(int start) {
        pos++; // ':'
        int nameStart = pos;
        while (pos < source.length() && isIdentifierPart(source.charAt(pos))) {
            pos++;
        }
        if (pos == nameStart) {
            throw new JpqlSyntaxException("Named parameter ':' at position " + start + " must be followed by a name");
        }
        return new JpqlToken(TokenType.NAMED_PARAM, source.substring(nameStart, pos), start);
    }

    private JpqlToken positionalParameter(int start) {
        pos++; // '?'
        int numStart = pos;
        while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
            pos++;
        }
        if (pos == numStart) {
            throw new JpqlSyntaxException(
                    "Positional parameter '?' at position " + start + " must be followed by a 1-based index");
        }
        return new JpqlToken(TokenType.POSITIONAL_PARAM, source.substring(numStart, pos), start);
    }

    private JpqlToken word(int start) {
        while (pos < source.length() && isIdentifierPart(source.charAt(pos))) {
            pos++;
        }
        String text = source.substring(start, pos);
        if (KEYWORDS.contains(text.toUpperCase(Locale.ROOT))) {
            return new JpqlToken(TokenType.KEYWORD, text, start);
        }
        return new JpqlToken(TokenType.IDENTIFIER, text, start);
    }

    private JpqlToken operator(int start) {
        char c = source.charAt(pos);
        switch (c) {
            case '(', ')', ',', '.', '+', '-', '*', '/' -> {
                pos++;
                return new JpqlToken(TokenType.OPERATOR, String.valueOf(c), start);
            }
            case '=' -> {
                pos++;
                return new JpqlToken(TokenType.OPERATOR, "=", start);
            }
            case '<' -> {
                pos++;
                if (pos < source.length() && source.charAt(pos) == '=') {
                    pos++;
                    return new JpqlToken(TokenType.OPERATOR, "<=", start);
                }
                if (pos < source.length() && source.charAt(pos) == '>') {
                    pos++;
                    return new JpqlToken(TokenType.OPERATOR, "<>", start);
                }
                return new JpqlToken(TokenType.OPERATOR, "<", start);
            }
            case '>' -> {
                pos++;
                if (pos < source.length() && source.charAt(pos) == '=') {
                    pos++;
                    return new JpqlToken(TokenType.OPERATOR, ">=", start);
                }
                return new JpqlToken(TokenType.OPERATOR, ">", start);
            }
            case '!' -> {
                pos++;
                if (pos < source.length() && source.charAt(pos) == '=') {
                    pos++;
                    return new JpqlToken(TokenType.OPERATOR, "<>", start);
                }
                throw new JpqlSyntaxException("Unexpected '!' at position " + start + "; did you mean '!='?");
            }
            default -> throw new JpqlSyntaxException(
                    "Unexpected character '" + c + "' at position " + start + " in JPQL");
        }
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
