package sh.harold.fulcrum.minigame.state.machine;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.minigame.state.MinigameState;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;

/**
 * Runtime state machine driving a single match.
 */
public final class StateMachine {
    private final JavaPlugin plugin;
    private final UUID matchId;
    private final Map<String, StateDefinition> graph;
    private final StateContext context;
    private final Consumer<String> stateListener;

    private String currentStateId;
    private MinigameState currentState;
    private volatile String requestedStateId;

    public StateMachine(JavaPlugin plugin,
                        UUID matchId,
                        Map<String, StateDefinition> graph,
                        StateContext context,
                        String startStateId,
                        Consumer<String> stateListener) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.graph = graph;
        this.context = context;
        this.currentStateId = Objects.requireNonNull(startStateId, "startStateId");
        this.stateListener = stateListener != null ? stateListener : s -> {};
        enterState(startStateId);
    }

    public void tick() {
        if (currentState == null) {
            return;
        }

        // Evaluate transitions prior to ticking
        evaluateTransitions();

        if (currentState != null) {
            currentState.onTick(context);
        }

        if (requestedStateId != null) {
            String target = requestedStateId;
            requestedStateId = null;
            switchState(target);
        }
    }

    public void publishEvent(MinigameEvent event) {
        if (currentState != null) {
            currentState.onEvent(context, event);
        }
    }

    public void requestTransition(String stateId) {
        requestedStateId = stateId;
    }

    public String getCurrentStateId() {
        return currentStateId;
    }

    private void evaluateTransitions() {
        StateDefinition definition = graph.get(currentStateId);
        if (definition == null) {
            return;
        }

        Optional<StateTransition> triggered = definition.getTransitions().stream()
            .filter(transition -> transition.shouldTransition(context))
            .findFirst();

        triggered.ifPresent(transition -> requestTransition(transition.getTargetStateId()));
    }

    private void switchState(String newStateId) {
        if (!graph.containsKey(newStateId)) {
            plugin.getLogger().warning("Match " + matchId + " attempted transition to unknown state " + newStateId);
            return;
        }
        if (Objects.equals(currentStateId, newStateId)) {
            return; // already active
        }

        if (currentState != null) {
            try {
                currentState.onExit(context);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error exiting state " + currentStateId + ": " + ex.getMessage());
            }
        }

        enterState(newStateId);
    }

    private void enterState(String stateId) {
        StateDefinition definition = graph.get(stateId);
        if (definition == null) {
            plugin.getLogger().warning("Match " + matchId + " missing definition for state " + stateId);
            currentState = null;
            return;
        }
        currentStateId = stateId;
        currentState = definition.getFactory().get();
        stateListener.accept(stateId);
        if (currentState != null) {
            try {
                currentState.onEnter(context);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error entering state " + stateId + ": " + ex.getMessage());
            }
        }
    }
}
