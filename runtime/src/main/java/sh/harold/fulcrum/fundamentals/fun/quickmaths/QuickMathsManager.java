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
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.minigame.MinigameEngine;

import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.time.Duration;
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
    private static final double EXACT_TOLERANCE = 0.0D;
    private static final ThreadLocal<DecimalFormat> DECIMAL_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("0.###");
        format.setGroupingUsed(false);
        return format;
    });

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

        scheduleTimeout(session, difficulty.timeLimit);

        broadcastStart(session, initiator);
        Message.success("Started Quick Maths with difficulty " + difficulty.name()
                        + ", " + winners + " winner(s). (Answer: " + formatPlain(session.equation.answer) + ")")
                .builder()
                .skipTranslation()
                .tag("staff")
                .send(initiator);
        return true;
    }

    public boolean cancelRound(CommandSender sender) {
        QuickMathsSession session = findSessionForCancellation(sender);
        if (session == null) {
            Message.error("No active Quick Maths round to cancel.")
                    .builder()
                    .skipTranslation()
                    .send(sender);
            return false;
        }

        Component body = Component.text("Cancelled: ", NamedTextColor.GRAY)
                .append(session.equation.display)
                .append(Component.text(" = ", NamedTextColor.GRAY))
                .append(Component.text(formatPlain(session.equation.answer), NamedTextColor.YELLOW));
        finishSession(session, body, sender, PrefixStyle.COMPLETE);
        Message.success("Cancelled Quick Maths for " + session.scope.displayName() + ".")
                .builder()
                .skipTranslation()
                .send(sender);
        return true;
    }

    private static String formatPlain(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return Long.toString(Math.round(value));
        }
        return DECIMAL_FORMAT.get().format(value);
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

    private QuickMathsSession findSessionForCancellation(CommandSender sender) {
        if (sender instanceof Player player) {
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
        }

        QuickMathsSession global = activeSessions.get(GLOBAL_SCOPE);
        if (global != null) {
            return global;
        }

        for (QuickMathsSession session : activeSessions.values()) {
            return session;
        }
        return null;
    }

    private void broadcastStart(QuickMathsSession session, CommandSender initiator) {
        Component body = Component.text("First ", NamedTextColor.GRAY)
                .append(Component.text(session.maxWinners == 1 ? "1 player" : session.maxWinners + " players", NamedTextColor.YELLOW))
                .append(Component.text(" to solve ", NamedTextColor.GRAY))
                .append(session.equation.display)
                .append(Component.text(" wins!", NamedTextColor.GRAY));
        sendToScope(session.scope, withPrefix(body, PrefixStyle.ACTIVE), initiator);
    }

    private static Component formatNumber(double value) {
        return Component.text(formatPlain(value), NamedTextColor.WHITE);
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

    private Component withPrefix(Component body, PrefixStyle style) {
        Component prefix = Component.text(style.label, NamedTextColor.LIGHT_PURPLE)
                .decorate(TextDecoration.BOLD);
        Component normalizedBody = body.decoration(TextDecoration.BOLD, false);
        return Component.text().append(prefix).append(normalizedBody).build();
    }

    private void scheduleTimeout(QuickMathsSession session, Duration limit) {
        if (limit == null || limit.isZero() || limit.isNegative()) {
            return;
        }
        long millis = limit.toMillis();
        long ticks = Math.max(1L, (millis + 49L) / 50L);
        long expiresAt = System.currentTimeMillis() + millis;
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> timeoutSession(session), ticks);
        session.trackTimeout(task, expiresAt);
    }

    private void timeoutSession(QuickMathsSession session) {
        Component body = Component.text("Timed out: ", NamedTextColor.GRAY)
                .append(session.equation.display)
                .append(Component.text(" = ", NamedTextColor.GRAY))
                .append(Component.text(formatPlain(session.equation.answer), NamedTextColor.YELLOW));
        finishSession(session, body, null, PrefixStyle.COMPLETE);
    }

    private void finishSession(QuickMathsSession session, Component body, CommandSender fallback, PrefixStyle style) {
        if (!activeSessions.remove(session.scope, session)) {
            session.cancelTimeout();
            return;
        }
        session.cancelTimeout();
        sendToScope(session.scope, withPrefix(body, style), fallback);
    }

    public void handleChat(Player player, String plainMessage) {
        if (player == null || plainMessage == null) {
            return;
        }
        String trimmed = plainMessage.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        QuickMathsSession session = findSessionForPlayer(player);
        if (session == null) {
            return;
        }
        if (session.isExpired()) {
            timeoutSession(session);
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

        double guess;
        try {
            guess = Double.parseDouble(trimmed.replace(",", ""));
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

    private void announceWinner(QuickMathsSession session, Player player, WinnerPlacement placement) {
        Component body = Component.text("#" + placement.position + " ", NamedTextColor.LIGHT_PURPLE)
                .append(player.displayName())
                .append(Component.space())
                .append(Component.text("answered in ", NamedTextColor.GRAY))
                .append(Component.text(placement.elapsedMillis + "ms", NamedTextColor.YELLOW));
        sendToScope(session.scope, withPrefix(body, PrefixStyle.ACTIVE), player);

        if (session.isComplete()) {
            Component overBody = session.equation.display
                    .append(Component.text(" = ", NamedTextColor.GRAY))
                    .append(Component.text(formatPlain(session.equation.answer), NamedTextColor.YELLOW));
            finishSession(session, overBody, null, PrefixStyle.COMPLETE);
        }
    }

    private QuickMathEquation generateEquation(Difficulty difficulty) {
        if (difficulty.advancedOps) {
            return generateAdvancedEquation(difficulty);
        }
        return generateIntegerEquation(difficulty);
    }

    private int randomOperand(Difficulty difficulty) {
        return ThreadLocalRandom.current().nextInt(difficulty.minOperand, difficulty.maxOperand + 1);
    }

    private QuickMathEquation generateIntegerEquation(Difficulty difficulty) {
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
        double answer = root.evaluate();
        return new QuickMathEquation(rendered, answer, EXACT_TOLERANCE);
    }

    private QuickMathEquation generateAdvancedEquation(Difficulty difficulty) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Expression> expressions = new ArrayList<>();
        for (int i = 0; i < difficulty.termCount; i++) {
            expressions.add(randomAdvancedTerminal(random, difficulty));
        }
        while (expressions.size() > 1) {
            Expression left = expressions.remove(random.nextInt(expressions.size()));
            Expression right = expressions.remove(random.nextInt(expressions.size()));
            Operation op = difficulty.randomOperation(secureRandom);
            boolean wrap = random.nextBoolean();
            if (op == Operation.DIVIDE) {
                right = ensureNonZero(right);
            }
            expressions.add(new BinaryExpression(left, right, op, wrap));
        }
        Expression root = expressions.get(0);
        if (difficulty.hasFunctions() && random.nextDouble() < difficulty.functionChance && difficulty.functionPool.length > 0) {
            FunctionOperation function = difficulty.randomFunction(secureRandom);
            root = new FunctionExpression(function, root);
        }
        if (difficulty.allowCalculus && random.nextDouble() < 0.5D) {
            Expression derivative = createDerivativeExpression(random);
            root = new BinaryExpression(root, derivative, Operation.ADD, true);
        }
        Component rendered = root.renderComponent(0, true);
        double answer = clampMagnitude(root.evaluate(), difficulty);
        return new QuickMathEquation(rendered, answer, difficulty.tolerance);
    }

    private double clampMagnitude(double value, Difficulty difficulty) {
        double limit = difficulty == Difficulty.NIGHTMARE ? 100_000D : 50_000D;
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return limit;
        }
        return Math.max(-limit, Math.min(limit, value));
    }

    private Expression randomAdvancedTerminal(ThreadLocalRandom random, Difficulty difficulty) {
        if (!difficulty.chaoticNumbers) {
            return new ValueExpression(random.nextInt(2, 16));
        }
        int roll = random.nextInt(6);
        return switch (roll) {
            case 0 -> new ConstantExpression("pi", Math.PI);
            case 1 -> new ConstantExpression("e", Math.E);
            case 2 -> new ValueExpression(random.nextInt(4, 24));
            case 3 -> new ValueExpression(random.nextInt(12, 48));
            case 4 -> new ConstantExpression("pi^2", Math.PI * Math.PI);
            default -> new ValueExpression(random.nextInt(2, 10) * random.nextInt(2, 6));
        };
    }

    private Expression ensureNonZero(Expression expression) {
        return new FunctionExpression(FunctionOperation.ABSOLUTE, expression);
    }

    private Expression createDerivativeExpression(ThreadLocalRandom random) {
        int coefficient = random.nextInt(2, 9);
        int exponent = random.nextInt(2, 6);
        int evaluationPoint = random.nextInt(1, 6);
        return new DerivativeExpression(coefficient, exponent, evaluationPoint);
    }

    public enum Difficulty {
        EASY(2, 6, 18, EnumSet.of(Operation.ADD, Operation.SUBTRACT), 0.0, false, false, 0.0, new FunctionOperation[0], false, 0.0, Duration.ofSeconds(30)),
        NORMAL(3, 4, 16, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.35, false, false, 0.0, new FunctionOperation[0], false, 0.0, Duration.ofSeconds(30)),
        HARD(4, 3, 14, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY), 0.55, false, false, 0.0, new FunctionOperation[0], false, 0.0, Duration.ofSeconds(30)),
        EXTREME(5, 2, 12, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY, Operation.POWER),
                0.7, true, false, 1.0, new FunctionOperation[0], false, 0.0, Duration.ofMinutes(10)),
        NIGHTMARE(6, 2, 10, EnumSet.of(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY, Operation.DIVIDE, Operation.POWER),
                0.85, true, true, 1.0, new FunctionOperation[]{FunctionOperation.SQRT, FunctionOperation.LN, FunctionOperation.SIN, FunctionOperation.COS, FunctionOperation.EXP}, true, 0.5, Duration.ofMinutes(10));

        private final int termCount;
        private final int minOperand;
        private final int maxOperand;
        private final EnumSet<Operation> operations;
        private final double wrapChance;
        private final boolean advancedOps;
        private final boolean allowCalculus;
        private final double tolerance;
        private final FunctionOperation[] functionPool;
        private final boolean chaoticNumbers;
        private final double functionChance;
        private final Duration timeLimit;

        Difficulty(int termCount,
                   int minOperand,
                   int maxOperand,
                   EnumSet<Operation> operations,
                   double wrapChance,
                   boolean advancedOps,
                   boolean allowCalculus,
                   double tolerance,
                   FunctionOperation[] functionPool,
                   boolean chaoticNumbers,
                   double functionChance,
                   Duration timeLimit) {
            this.termCount = termCount;
            this.minOperand = minOperand;
            this.maxOperand = maxOperand;
            this.operations = operations;
            this.wrapChance = wrapChance;
            this.advancedOps = advancedOps;
            this.allowCalculus = allowCalculus;
            this.tolerance = tolerance;
            this.functionPool = functionPool;
            this.chaoticNumbers = chaoticNumbers;
            this.functionChance = functionChance;
            this.timeLimit = timeLimit == null ? Duration.ofSeconds(30) : timeLimit;
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

        private FunctionOperation randomFunction(SecureRandom random) {
            if (functionPool.length == 0) {
                return FunctionOperation.SQRT;
            }
            return functionPool[random.nextInt(functionPool.length)];
        }

        private boolean hasFunctions() {
            return functionPool.length > 0;
        }
    }

    private enum Operation {
        ADD("+") {
            @Override
            double eval(double a, double b) {
                return a + b;
            }
        },
        SUBTRACT("-") {
            @Override
            double eval(double a, double b) {
                return a - b;
            }
        },
        MULTIPLY("*") {
            @Override
            double eval(double a, double b) {
                return a * b;
            }
        },
        DIVIDE("/") {
            @Override
            double eval(double a, double b) {
                double denominator = Math.abs(b) < 0.001D ? 1.0D : b;
                return a / denominator;
            }
        },
        POWER("^") {
            @Override
            double eval(double a, double b) {
                double base = clampMagnitude(a, 6.0D);
                double exponent = clampMagnitude(b, 4.0D);
                double result = Math.pow(base, exponent);
                if (Double.isInfinite(result) || Double.isNaN(result)) {
                    return 100_000D;
                }
                return Math.max(-100_000D, Math.min(100_000D, result));
            }
        };

        private final String symbol;

        Operation(String symbol) {
            this.symbol = symbol;
        }

        private static double clampMagnitude(double value, double maxMagnitude) {
            return Math.max(-maxMagnitude, Math.min(maxMagnitude, value));
        }

        abstract double eval(double a, double b);
    }

    private enum ScopeType {
        GLOBAL,
        SLOT
    }

    private enum FunctionOperation {
        SQRT("sqrt") {
            @Override
            double apply(double input) {
                return Math.sqrt(Math.abs(input));
            }
        },
        LN("ln") {
            @Override
            double apply(double input) {
                return Math.log(Math.max(Math.abs(input), 0.001D));
            }
        },
        EXP("exp") {
            @Override
            double apply(double input) {
                return Math.exp(Math.min(input, 5.0D));
            }
        },
        SIN("sin") {
            @Override
            double apply(double input) {
                return Math.sin(input);
            }
        },
        COS("cos") {
            @Override
            double apply(double input) {
                return Math.cos(input);
            }
        },
        ABSOLUTE("abs") {
            @Override
            double apply(double input) {
                double value = Math.abs(input);
                return value < 0.001D ? 1.0D : value;
            }
        };

        private final String display;

        FunctionOperation(String display) {
            this.display = display;
        }

        abstract double apply(double input);
    }

    private sealed interface Expression permits BinaryExpression, ValueExpression, ConstantExpression, FunctionExpression, DerivativeExpression {
        double evaluate();

        Component renderComponent(int depth, boolean root);
    }

    private record ValueExpression(double value) implements Expression {

        @Override
            public double evaluate() {
                return value;
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                return formatNumber(value);
            }
        }

    private record BinaryExpression(Expression left, Expression right, Operation operation,
                                    boolean forceWrap) implements Expression {

        @Override
            public double evaluate() {
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

    private record ConstantExpression(String label, double value) implements Expression {

        @Override
            public double evaluate() {
                return value;
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                return Component.text(label, NamedTextColor.AQUA);
            }
        }

    private record FunctionExpression(FunctionOperation function, Expression input) implements Expression {

        @Override
            public double evaluate() {
                return function.apply(input.evaluate());
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                TextComponent.Builder builder = Component.text();
                builder.append(Component.text(function.display + "(", NamedTextColor.DARK_AQUA));
                builder.append(input.renderComponent(depth + 1, false));
                builder.append(Component.text(")", NamedTextColor.DARK_AQUA));
                return builder.build();
            }
        }

    private record DerivativeExpression(int coefficient, int exponent, int evaluationPoint) implements Expression {

        @Override
            public double evaluate() {
                return coefficient * exponent * Math.pow(evaluationPoint, exponent - 1);
            }

            @Override
            public Component renderComponent(int depth, boolean root) {
                TextComponent.Builder builder = Component.text();
                builder.append(Component.text("d/dx ", NamedTextColor.BLUE));
                builder.append(Component.text("(" + coefficient + "x^" + exponent + ")", NamedTextColor.WHITE));
                builder.append(Component.text(" | x = ", NamedTextColor.GRAY));
                builder.append(Component.text(evaluationPoint, NamedTextColor.YELLOW));
                return builder.build();
            }
        }

    private enum PrefixStyle {
        ACTIVE("QUICK MATHS! "),
        COMPLETE("QUICK MATHS OVER! ");

        private final String label;

        PrefixStyle(String label) {
            this.label = label;
        }
    }

    private record QuickMathEquation(Component display, double answer, double tolerance) {
    }

    private static final class QuickMathsSession {
        private final QuickMathsScope scope;
        private final int maxWinners;
        private final QuickMathEquation equation;
        private final long startedAt;
        private final LinkedHashSet<UUID> winners = new LinkedHashSet<>();
        private BukkitTask timeoutTask;
        private long expiresAt;

        private QuickMathsSession(QuickMathsScope scope,
                                  int maxWinners,
                                  QuickMathEquation equation) {
            this.scope = scope;
            this.maxWinners = maxWinners;
            this.equation = equation;
            this.startedAt = System.currentTimeMillis();
        }

        private boolean matchesAnswer(double guess) {
            return Math.abs(guess - equation.answer) <= equation.tolerance;
        }

        private void trackTimeout(BukkitTask task, long expiresAt) {
            cancelTimeout();
            this.timeoutTask = task;
            this.expiresAt = expiresAt;
        }

        private void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
            expiresAt = 0L;
        }

        private boolean isExpired() {
            return expiresAt > 0L && System.currentTimeMillis() >= expiresAt;
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
