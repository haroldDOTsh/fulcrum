package sh.harold.fulcrum.capability.runtime;

import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationError;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CapabilityContributionComposer {
    private static final Comparator<CapabilityMaterializationPlan.ContributionRegistration> CONTRIBUTION_ORDER =
            Comparator.comparingInt((CapabilityMaterializationPlan.ContributionRegistration registration) ->
                            registration.declaration().order())
                    .thenComparing(registration -> registration.capabilityId().value());

    private CapabilityContributionComposer() {
    }

    public static CapabilityValidationResult validate(CapabilityMaterializationPlan plan) {
        CapabilityMaterializationPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        List<CapabilityValidationError> errors = new ArrayList<>();
        Set<String> slots = new HashSet<>();
        for (CapabilityMaterializationPlan.ContributionRegistration registration : checkedPlan.contributions()) {
            String slot = slot(registration);
            if (!slots.add(slot)) {
                errors.add(new CapabilityValidationError("composition.contribution.slot.duplicate", slot));
            }
        }
        return new CapabilityValidationResult(errors);
    }

    public static CapabilityValidationResult validate(CapabilityMaterializationPlan plan, CapabilityScope scope) {
        CapabilityMaterializationPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        CapabilityScope checkedScope = Objects.requireNonNull(scope, "scope");
        List<CapabilityValidationError> errors = new ArrayList<>(validate(checkedPlan).errors());
        Set<String> effectiveSlots = new HashSet<>();
        for (CapabilityMaterializationPlan.ContributionRegistration registration : checkedPlan.contributions()) {
            if (!registration.declaration().scope().permits(checkedScope)) {
                continue;
            }
            String effectiveSlot = registration.declaration().extensionPoint().wireName()
                    + "|" + checkedScope.value()
                    + "|" + registration.declaration().order();
            if (!effectiveSlots.add(effectiveSlot)) {
                errors.add(new CapabilityValidationError(
                        "composition.contribution.effective-order.duplicate",
                        effectiveSlot));
            }
        }
        return new CapabilityValidationResult(errors);
    }

    public static CapabilityContributionComposition compose(CapabilityMaterializationPlan plan, CapabilityScope scope) {
        CapabilityMaterializationPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        CapabilityScope checkedScope = Objects.requireNonNull(scope, "scope");
        CapabilityValidationResult validationResult = validate(checkedPlan, checkedScope);
        if (!validationResult.valid()) {
            throw new IllegalArgumentException("invalid capability contribution composition: " + validationResult.errors());
        }

        Map<CapabilityExtensionPoint, List<CapabilityMaterializationPlan.ContributionRegistration>> grouped =
                new EnumMap<>(CapabilityExtensionPoint.class);
        for (CapabilityExtensionPoint extensionPoint : CapabilityExtensionPoint.values()) {
            grouped.put(extensionPoint, new ArrayList<>());
        }
        for (CapabilityMaterializationPlan.ContributionRegistration registration : checkedPlan.contributions()) {
            if (registration.declaration().scope().permits(checkedScope)) {
                grouped.get(registration.declaration().extensionPoint()).add(registration);
            }
        }

        List<CapabilityContributionPipeline> pipelines = new ArrayList<>();
        for (Map.Entry<CapabilityExtensionPoint, List<CapabilityMaterializationPlan.ContributionRegistration>> entry
                : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<CapabilityMaterializationPlan.ContributionRegistration> ordered = entry.getValue().stream()
                    .sorted(CONTRIBUTION_ORDER)
                    .toList();
            pipelines.add(new CapabilityContributionPipeline(entry.getKey(), checkedScope, ordered));
        }
        return new CapabilityContributionComposition(checkedScope, pipelines);
    }

    private static String slot(CapabilityMaterializationPlan.ContributionRegistration registration) {
        return registration.declaration().extensionPoint().wireName()
                + "|" + registration.declaration().scope().value()
                + "|" + registration.declaration().order();
    }
}
