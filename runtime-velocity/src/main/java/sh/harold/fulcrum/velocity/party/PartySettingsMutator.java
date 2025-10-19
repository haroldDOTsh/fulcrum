package sh.harold.fulcrum.velocity.party;

import sh.harold.fulcrum.api.party.PartySettings;

@FunctionalInterface
interface PartySettingsMutator {
    void apply(PartySettings settings);
}
