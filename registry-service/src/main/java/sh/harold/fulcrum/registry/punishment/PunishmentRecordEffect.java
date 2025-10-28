package sh.harold.fulcrum.registry.punishment;

import sh.harold.fulcrum.api.punishment.PunishmentEffectType;

import java.time.Duration;
import java.time.Instant;

record PunishmentRecordEffect(PunishmentEffectType type, Duration duration, Instant expiresAt, String message) {

}
