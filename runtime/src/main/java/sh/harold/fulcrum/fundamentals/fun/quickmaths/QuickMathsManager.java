package sh.harold.fulcrum.fundamentals.fun.quickmaths;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.minigame.MinigameEngine;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Coordinates Quick Maths rounds and routes chat answers to the appropriate scope.
 */
public final class QuickMathsManager {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final NamedTextColor[] PARENTHESIS_COLORS = new NamedTextColor[]{
            NamedTextColor.GRAY,
            NamedTextColor.YELLOW,
            NamedTextColor.GOLD,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.AQUA
    };
    private static final QuickMathsScope GLOBAL_SCOPE = QuickMathsScope.global();
    private static final int MAX_WINNERS = 10;

    private final JavaPlugin plugin;
    private final Supplier<MinigameEngine> engineSupplier;
    private final Map<QuickMathsScope, QuickMathsSession> activeSessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public QuickMathsManager(JavaPlugin plugin, Supplier<MinigameEngine> engineSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    public int maxWinnersPerRound() {
        return MAX_WINNERS;
    }

    public boolean startRound(CommandSender initiator, Difficulty difficulty, int winners) {
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(difficulty, "difficulty");
        if (winners < 1 || winners > MAX_WINNERS) {
            Message.error("Number of winners must be between 1 and " + MAX_WINNERS + ".")
                    .builder()
                    .skipTranslation()
                    .send(initiator);
            return false;
        }

        ScopeResolution resolution = resolveScope(initiator);
        if (!resolution.allowed()) {
            Message.error(resolution.reason())
                    .builder()
                    .skipTranslation()
                    .send(initiator);
            return false;
        }

        QuickMathsScope scope = resolution.scope();
        QuickMathsSession session = new QuickMathsSession(scope, winners, generateEquation(difficulty));
        QuickMathsSession existing = activeSessions.putIfAbsent(scope, session);
        if (existing != null) {
            Message.error("A Quick Maths round is already active for " + scope.displayName() + ".")
                    .builder()
                    .skipTranslation()
                    .send(initiator);
            return false;
        }

        broadcastStart(session, initiator);
        Message.success("Started Quick Maths (" + difficulty.name() + ", " + winners + " winner(s)) for " + scope.displayName() + ".")
                .builder()
                .skipTranslation()
                .send(initiator);
        return true;
    }

    public void handleChat(Player player, String plainMessage) {
        if (player == null || plainMessage == null) {
            return;
        }
        String trimmed = plainMessage.trim();
        if (trimmed.isEmpty() || !trimmed.matches("-?\\d+")) {
            return;
        }

        QuickMathsSession session = findSessionForPlayer(player);
        if (session == null) {
            return;
        }
        if (session.scope.type == ScopeType.SLOT) {
            MinigameEngine engine = engineSupplier.get();
            if (engine == null) {
                return;
            }
            Optional<String> slotId = engine.resolveSlotId(player.getUniqueId());
            if (slotId.isEmpty() || !slotId.get().equalsIgnoreCase(session.scope.slotId)) {
                return;
            }
        }

        long guess;
        try {
            guess = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return;
        }
        if (!session.matchesAnswer(guess)) {
            return;
        }

        WinnerPlacement placement = session.tryRecordWinner(player.getUniqueId());
        if (placement == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> announceWinner(session, player, placement));
    }

    private ScopeResolution resolveScope(CommandSender initiator) {
        MinigameEngine engine = engineSupplier.get();
        if (engine == null) {
            return ScopeResolution.allowed(GLOBAL_SCOPE);
        }

        if (initiator instanceof Player player) {
            Optional<String> slot = engine.resolveSlotId(player.getUniqueId());
            if (slot.isPresent()) {
                return ScopeResolution.allowed(QuickMathsScope.slot(slot.get()));
            }
        }

        if (hasActiveMinigamePlayers(engine)) {
            return ScopeResolution.denied("Quick Maths must target a specific match on this server.");
        }
        return ScopeResolution.allowed(GLOBAL_SCOPE);
    }

    private boolean hasActiveMinigamePlayers(MinigameEngine engine) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (engine.resolveSlotId(online.getUniqueId()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private QuickMathsSession findSessionForPlayer(Player player) {
        MinigameEngine engine = engineSupplier.get();
        if (engine != null) {
            Optional<String> slot = engine.resolveSlotId(player.getUniqueId());
            if (slot.isPresent()) {
                QuickMathsScope scope = QuickMathsScope.slot(slot.get());
                QuickMathsSession scoped = activeSessions.get(scope);
                if (scoped != null) {
                    return scoped;
                }
            }
        }
        return activeSessions.get(GLOBAL_SCOPE);
    }

    private void broadcastStart(QuickMathsSession session, CommandSender initiator) {
        Component prefix = prefixComponent();
        Component announcement = prefix.append(Component.text("First ", NamedTextColor.GRAY))
                .append(Component.text(session.maxWinners == 1 ? "1 player" : session.maxWinners + " players", NamedTextColor.YELLOW))
                .append(Component.text(" to solve ", NamedTextColor.GRAY))
                .append(session.equation.display)
                .append(Component.text(" wins!", NamedTextColor.GRAY));
        sendToScope(session.scope, announcement, initiator);
    }

    private void announceWinner(QuickMathsSession session, Player player, WinnerPlacement placement) {
        Component base = prefixComponent()
                .append(Component.text("#" + placement.position + " ", NamedTextColor.LIGHT_PURPLE))
                .append(player.displayName())
                .append(Component.space())
                .append(Component.text("answered in ", NamedTextColor.GRAY))
                .append(Component.text(placement.elapsedMillis + "ms", NamedTextColor.YELLOW));
        sendToScope(session.scope, base, player);

        if (session.isComplete()) {
            activeSessions.remove(session.scope, session);
            Component over = prefixComponent()
                    .append(Component.text("OVER! ", NamedTextColor.GRAY))
                    .append(session.equation.display)
                    .append(Component.text(" = ", NamedTextColor.GRAY))
                    .append(Component.text(session.equation.answer, NamedTextColor.YELLOW));
            sendToScope(session.scope, over, null);
        }
    }

    private void sendToScope(QuickMathsScope scope, Component message, CommandSender fallback) {
        Collection<Player> recipients = resolveRecipients(scope);
        if (recipients.isEmpty() && fallback instanceof Player player) {
            player.sendMessage(message);
            return;
        }
        for (Player recipient : recipients) {
            recipient.sendMessage(message);
        }
        if (fallback != null && !(fallback instanceof Player)) {
            fallback.sendMessage(PLAIN.serialize(message));
        }
    }

    private Collection<Player> resolveRecipients(QuickMathsScope scope) {
        if (scope.type == ScopeType.GLOBAL) {
            return new ArrayList<>(plugin.getServer().getOnlinePlayers());
        }
        MinigameEngine engine = engineSupplier.get();
        if (engine == null) {
            return List.of();
        }
        return new ArrayList<>(engine.getPlayersInSlot(scope.slotId));
    }

    private Component prefixComponent() {
        return Component.text("QUICK MATHS! ", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true);
    }

    private QuickMathEquation generateEquation(Difficulty difficulty) {
        int termCount = difficulty.termCount;
        List<Expression> expressions = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < termCount; i++) {
            expressions.add(new ValueExpression(randomOperand(difficulty)));
        }
        while (expressions.size() > 1) {
            Expression left = expressions.remove(random.nextInt(expressions.size()));
            Expression right = expressions.remove(random.nextInt(expressions.size()));
            Operation op = difficulty.randomOperation(secureRandom);
            boolean wrap = random.nextDouble() < difficulty.wrapChance;
            expressions.add(new BinaryExpression(left, right, op, wrap));
        }
        Expression root = expressions.get(0);
        Component rendered = root.renderComponent(0, true);
        long answer = root.evaluate();
        return new QuickMathEquation(rendered, answer);
    }

    private int randomOperand(Difficulty difficulty) {
        return ThreadLocalRandom.current().nextInt(difficulty.minOperand, difficulty.maxOperand + 1);
    }

    public enum Difficulty {
        EASY(2, 6, 18, EnumSet.of(Operation.ADD, Operation.SUBTRACT), 0.0),
        NORMAL(3, 4, 16, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.35),
        HARD(4, 3, 14, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.55),
        EXTREME(5, 2, 12, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.7),
        NIGHTMARE(6, 2, 10, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.85);

        private final int termCount;
        private final int minOperand;
        private final int maxOperand;
        private final EnumSet<Operation> operations;
        private final double wrapChance;

        Difficulty(int termCount,
                   int minOperand,
                   int maxOperand,
                   EnumSet<Operation> operations,
                   double wrapChance) {
            this.termCount = termCount;
            this.minOperand = minOperand;
            this.maxOperand = maxOperand;
            this.operations = operations;
            this.wrapChance = wrapChance;
        }

        public static Optional<Difficulty> parse(String input) {
            if (input == null || input.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Difficulty.valueOf(input.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        private Operation randomOperation(SecureRandom random) {
            Operation[] values = operations.toArray(Operation[]::new);
            return values[random.nextInt(values.length)];
        }
    }

    private enum Operation {
        ADD("+") {
            @Override
            long eval(long a, long b) {
                return a + b;
            }
        },
        SUBTRACT("-") {
            @Override
            long eval(long a, long b) {
                return a - b;
            }
        },
        MULTIPLY("*") {
            @Override
            long eval(long a, long b) {
                return a * b;
            }
        };

        private final String symbol;

        Operation(String symbol) {
            this.symbol = symbol;
        }

        abstract long eval(long a, long b);
    }

    private enum ScopeType {
        GLOBAL,
        SLOT
    }

    private sealed interface Expression permits BinaryExpression, ValueExpression {
        long evaluate();

        Component renderComponent(int depth, boolean root);
    }

    private record ValueExpression(int value) implements Expression {

        @Override
            public long evaluate() {
                return value;
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                return Component.text(value, NamedTextColor.WHITE);
            }
        }

    private record BinaryExpression(Expression left, Expression right, Operation operation,
                                    boolean forceWrap) implements Expression {

        @Override
            public long evaluate() {
                return operation.eval(left.evaluate(), right.evaluate());
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                TextComponent.Builder builder = Component.text();
                boolean wrap = !root || forceWrap;
                NamedTextColor parenColor = PARENTHESIS_COLORS[depth % PARENTHESIS_COLORS.length];
                if (wrap) {
                    builder.append(Component.text("(", parenColor));
                }
                builder.append(left.renderComponent(depth + 1, false));
                builder.append(Component.text(" " + operation.symbol + " ", NamedTextColor.DARK_GRAY));
                builder.append(right.renderComponent(depth + 1, false));
                if (wrap) {
                    builder.append(Component.text(")", parenColor));
                }
                return builder.build();
            }
        }

    private record QuickMathEquation(Component display, long answer) {
    }

    private static final class QuickMathsSession {
        private final QuickMathsScope scope;
        private final int maxWinners;
        private final QuickMathEquation equation;
        private final long startedAt;
        private final LinkedHashSet<UUID> winners = new LinkedHashSet<>();

        private QuickMathsSession(QuickMathsScope scope,
                                  int maxWinners,
                                  QuickMathEquation equation) {
            this.scope = scope;
            this.maxWinners = maxWinners;
            this.equation = equation;
            this.startedAt = System.currentTimeMillis();
        }

        private boolean matchesAnswer(long guess) {
            return guess == equation.answer;
        }

        private WinnerPlacement tryRecordWinner(UUID playerId) {
            synchronized (winners) {
                if (winners.contains(playerId) || winners.size() >= maxWinners) {
                    return null;
                }
                winners.add(playerId);
                int position = winners.size();
                long elapsed = System.currentTimeMillis() - startedAt;
                return new WinnerPlacement(position, elapsed);
            }
        }

        private boolean isComplete() {
            synchronized (winners) {
                return winners.size() >= maxWinners;
            }
        }
    }

    private record ScopeResolution(boolean allowed, QuickMathsScope scope, String reason) {
        static ScopeResolution allowed(QuickMathsScope scope) {
            return new ScopeResolution(true, scope, null);
        }

        static ScopeResolution denied(String reason) {
            return new ScopeResolution(false, null, reason);
        }
    }

    private record QuickMathsScope(ScopeType type, String slotId) {
        private static QuickMathsScope global() {
            return new QuickMathsScope(ScopeType.GLOBAL, null);
        }

        private static QuickMathsScope slot(String slotId) {
            return new QuickMathsScope(ScopeType.SLOT, slotId == null ? null : slotId.toLowerCase(Locale.ROOT));
        }

        private String displayName() {
            return type == ScopeType.GLOBAL ? "this server" : "slot " + slotId;
        }
    }

    private record WinnerPlacement(int position, long elapsedMillis) {
    }
}
