package sh.harold.fulcrum.fundamentals.minigame.debug;

/**
 * Persistent player data for the debug minigame.
 */
public final class DebugPlayerData {
    private final LetterCounters choseLetter = new LetterCounters();
    private int wins;
    private int losses;
    private int deaths;

    public LetterCounters getChoseLetter() {
        return choseLetter;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getDeaths() {
        return deaths;
    }

    public void recordLetter(char letter) {
        switch (Character.toLowerCase(letter)) {
            case 'a' -> choseLetter.incrementA();
            case 'b' -> choseLetter.incrementB();
            case 'c' -> choseLetter.incrementC();
            case 'd' -> choseLetter.incrementD();
            default -> {
            }
        }
    }

    public void incrementWins() {
        wins++;
    }

    public void incrementLosses() {
        losses++;
    }

    public void incrementDeaths() {
        deaths++;
    }

    public static final class LetterCounters {
        private int a;
        private int b;
        private int c;
        private int d;

        public int getA() {
            return a;
        }

        public int getB() {
            return b;
        }

        public int getC() {
            return c;
        }

        public int getD() {
            return d;
        }

        private void incrementA() {
            a++;
        }

        private void incrementB() {
            b++;
        }

        private void incrementC() {
            c++;
        }

        private void incrementD() {
            d++;
        }
    }
}
