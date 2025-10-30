package sh.harold.fulcrum.velocity.fundamentals.motd;

final class CenteredTextFormatter {

    private static final int CENTER_PX = 154;
    private static final char SECTION_CHAR = '§';
    private static final char LEGACY_CHAR = '&';

    String center(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        int pixelWidth = measure(input);
        if (pixelWidth <= 0) {
            return input;
        }

        int paddingPixels = CENTER_PX - (pixelWidth / 2);
        if (paddingPixels <= 0) {
            return input;
        }

        int spaceWidth = FontWidth.SPACE.getLength() + 1;
        int spaces = Math.max(0, paddingPixels / spaceWidth);
        if (spaces == 0) {
            return input;
        }

        return " ".repeat(spaces) + input;
    }

    private int measure(String input) {
        int width = 0;
        boolean checkingCode = false;
        boolean bold = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == SECTION_CHAR || c == LEGACY_CHAR) {
                checkingCode = true;
                continue;
            }

            if (checkingCode) {
                checkingCode = false;
                char lower = Character.toLowerCase(c);
                if (lower == 'l') {
                    bold = true;
                } else if (lower == 'r' || isColorCode(lower)) {
                    bold = false;
                }
                continue;
            }

            FontWidth info = FontWidth.of(c);
            width += bold ? info.getBoldLength() : info.getLength();
            width++;
        }

        return width;
    }

    private boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private enum FontWidth {
        SPACE(' ', 4),
        EXCLAMATION_POINT('!', 2),
        QUOTATION_MARK('"', 3),
        NUMBER_SIGN('#', 5),
        DOLLAR_SIGN('$', 5),
        PERCENT('%', 5),
        AMPERSAND('&', 5),
        APOSTROPHE('\'', 2),
        LEFT_PARENTHESIS('(', 4),
        RIGHT_PARENTHESIS(')', 4),
        ASTERISK('*', 4),
        PLUS('+', 5),
        COMMA(',', 2),
        MINUS('-', 5),
        PERIOD('.', 2),
        SLASH('/', 5),
        ZERO('0', 5),
        ONE('1', 5),
        TWO('2', 5),
        THREE('3', 5),
        FOUR('4', 5),
        FIVE('5', 5),
        SIX('6', 5),
        SEVEN('7', 5),
        EIGHT('8', 5),
        NINE('9', 5),
        COLON(':', 2),
        SEMICOLON(';', 2),
        LESS_THAN('<', 5),
        EQUALS('=', 5),
        GREATER_THAN('>', 5),
        QUESTION_MARK('?', 5),
        AT('@', 6),
        A('A', 5),
        B('B', 5),
        C('C', 5),
        D('D', 5),
        E('E', 5),
        F('F', 5),
        G('G', 5),
        H('H', 5),
        I('I', 3),
        J('J', 5),
        K('K', 5),
        L('L', 5),
        M('M', 5),
        N('N', 5),
        O('O', 5),
        P('P', 5),
        Q('Q', 5),
        R('R', 5),
        S('S', 5),
        T('T', 5),
        U('U', 5),
        V('V', 5),
        W('W', 5),
        X('X', 5),
        Y('Y', 5),
        Z('Z', 5),
        LEFT_BRACKET('[', 4),
        BACKSLASH('\\', 5),
        RIGHT_BRACKET(']', 4),
        CARET('^', 5),
        UNDERSCORE('_', 5),
        GRAVE('`', 3),
        a('a', 5),
        b('b', 5),
        c('c', 5),
        d('d', 5),
        e('e', 5),
        f('f', 4),
        g('g', 5),
        h('h', 5),
        i('i', 2),
        j('j', 5),
        k('k', 4),
        l('l', 2),
        m('m', 5),
        n('n', 5),
        o('o', 5),
        p('p', 5),
        q('q', 5),
        r('r', 5),
        s('s', 5),
        t('t', 4),
        u('u', 5),
        v('v', 5),
        w('w', 5),
        x('x', 5),
        y('y', 5),
        z('z', 5),
        LEFT_BRACE('{', 4),
        PIPE('|', 2),
        RIGHT_BRACE('}', 4),
        TILDE('~', 6),
        DEFAULT('?', 4);

        private final char character;
        private final int length;

        FontWidth(char character, int length) {
            this.character = character;
            this.length = length;
        }

        static FontWidth of(char c) {
            for (FontWidth value : values()) {
                if (value.character == c) {
                    return value;
                }
            }
            return DEFAULT;
        }

        int getLength() {
            return length;
        }

        int getBoldLength() {
            return length + 1;
        }
    }
}
