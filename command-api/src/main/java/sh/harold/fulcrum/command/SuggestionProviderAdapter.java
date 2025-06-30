package sh.harold.fulcrum.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface SuggestionProviderAdapter {
    CompletableFuture<Suggestions> apply(CommandContext<?> ctx, SuggestionsBuilder builder);
}
