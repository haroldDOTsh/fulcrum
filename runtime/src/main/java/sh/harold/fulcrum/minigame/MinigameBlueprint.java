package sh.harold.fulcrum.minigame;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import sh.harold.fulcrum.minigame.defaults.DefaultEndGameState;
import sh.harold.fulcrum.minigame.defaults.DefaultInGameState;
import sh.harold.fulcrum.minigame.defaults.DefaultPreLobbyState;
import sh.harold.fulcrum.minigame.defaults.DefaultStates;
import sh.harold.fulcrum.minigame.defaults.InGameHandler;
import sh.harold.fulcrum.minigame.defaults.PreLobbyOptions;
import sh.harold.fulcrum.minigame.state.MinigameState;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.machine.StateDefinition;
import sh.harold.fulcrum.minigame.state.machine.StateTransition;

/**
 * Declarative blueprint that describes the lifecycle of a minigame match.
 */
public final class MinigameBlueprint {
    public static final String STATE_PRE_LOBBY = "pre_lobby";
    public static final String STATE_IN_GAME = "in_game";
    public static final String STATE_END_GAME = "end_game";

    private final Map<String, StateDefinition> stateGraph;
    private final String startStateId;

    private MinigameBlueprint(Map<String, StateDefinition> stateGraph, String startStateId) {
        this.stateGraph = Collections.unmodifiableMap(new HashMap<>(stateGraph));
        this.startStateId = startStateId;
    }

    public Map<String, StateDefinition> getStateGraph() {
        return stateGraph;
    }

    public String getStartStateId() {
        return startStateId;
    }

    /**
     * Create a standard blueprint (Pre-Lobby → In-Game → End-Game).
     */
    public static StandardBuilder standard() {
        return new StandardBuilder();
    }

    /**
     * Create a custom builder.
     */
    public static CustomBuilder custom() {
        return new CustomBuilder();
    }

    /**
     * Builder for standard three-phase match.
     */
    public static final class StandardBuilder {
        private PreLobbyOptions preLobbyOptions = DefaultStates.preLobby().build();
        private InGameHandler inGameHandler = DefaultStates.inGame().build();
        private Supplier<DefaultEndGameState> endGameFactory = DefaultStates::endGame;
        private Predicate<StateContext> inGameCompletionPredicate =
            context -> Boolean.TRUE.equals(context.getAttributeOptional(MinigameAttributes.MATCH_COMPLETE, Boolean.class).orElse(Boolean.FALSE));

        public StandardBuilder preLobby(java.util.function.Consumer<DefaultStates.PreLobbyBuilder> customizer) {
            DefaultStates.PreLobbyBuilder builder = DefaultStates.preLobby();
            customizer.accept(builder);
            this.preLobbyOptions = builder.build();
            return this;
        }

        public StandardBuilder inGame(InGameHandler handler) {
            this.inGameHandler = handler;
            return this;
        }

        public StandardBuilder endGame(Supplier<DefaultEndGameState> factory) {
            this.endGameFactory = factory;
            return this;
        }

        public StandardBuilder onInGameComplete(Predicate<StateContext> predicate) {
            this.inGameCompletionPredicate = predicate;
            return this;
        }

        public MinigameBlueprint build() {
            Map<String, StateDefinition> graph = new HashMap<>();

            StateDefinition preLobby = new StateDefinition(() -> new DefaultPreLobbyState(preLobbyOptions, STATE_IN_GAME));
            graph.put(STATE_PRE_LOBBY, preLobby);

            StateDefinition inGame = new StateDefinition(() -> new DefaultInGameState(inGameHandler));
            inGame.addTransition(new StateTransition(STATE_END_GAME, inGameCompletionPredicate));
            graph.put(STATE_IN_GAME, inGame);

            StateDefinition endGame = new StateDefinition(endGameFactory);
            graph.put(STATE_END_GAME, endGame);

            return new MinigameBlueprint(graph, STATE_PRE_LOBBY);
        }
    }

    /**
     * Builder for fully custom state graphs.
     */
    public static final class CustomBuilder {
        private final Map<String, StateDefinition> graph = new HashMap<>();
        private String startStateId;

        public CustomBuilder addState(String stateId, Supplier<? extends MinigameState> factory) {
            graph.put(stateId, new StateDefinition(() -> factory.get()));
            return this;
        }

        public CustomBuilder addTransition(String fromState, String toState, Predicate<StateContext> condition) {
            StateDefinition definition = graph.get(fromState);
            if (definition == null) {
                throw new IllegalStateException("State " + fromState + " is not registered");
            }
            definition.addTransition(new StateTransition(toState, condition));
            return this;
        }

        public CustomBuilder startState(String stateId) {
            this.startStateId = stateId;
            return this;
        }

        public MinigameBlueprint build() {
            if (startStateId == null) {
                throw new IllegalStateException("Start state must be specified");
            }
            return new MinigameBlueprint(graph, startStateId);
        }
    }
}
