package sh.harold.fulcrum.npc;

import sh.harold.fulcrum.npc.validation.NpcDefinitionValidator;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry storing NPC definitions.
 */
public final class NpcRegistry {
    private final Map<String, NpcDefinition> definitions = new ConcurrentHashMap<>();
    private final NpcDefinitionValidator validator;

    public NpcRegistry() {
        this(new NpcDefinitionValidator());
    }

    public NpcRegistry(NpcDefinitionValidator validator) {
        this.validator = validator;
    }

    public void register(NpcDefinition definition) {
        validator.validateOrThrow(definition);
        NpcDefinition previous = definitions.putIfAbsent(definition.id(), definition);
        if (previous != null) {
            throw new IllegalArgumentException("NPC id already registered: " + definition.id());
        }
    }

    public void registerAll(Collection<NpcDefinition> newDefinitions) {
        for (NpcDefinition definition : newDefinitions) {
            register(definition);
        }
        validator.validateAllOrThrow(definitions.values());
    }

    public Optional<NpcDefinition> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(id));
    }

    public Collection<NpcDefinition> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public int size() {
        return definitions.size();
    }

    public void clear() {
        definitions.clear();
    }
}
