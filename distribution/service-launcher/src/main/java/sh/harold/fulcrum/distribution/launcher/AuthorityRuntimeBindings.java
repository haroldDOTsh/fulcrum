package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;

import java.util.function.Supplier;

interface AuthorityRuntimeBindings {
    <C extends CommandPayload> AuthorityCommandSource<C> commandSource(String authorityDomain);

    <S> AuthorityRecordStore<S> recordStore(String authorityDomain, Supplier<AuthorityRecord<S>> emptyRecord);

    <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> projectionWriter(String authorityDomain);

    AuthorityEmissionSink emissionSink(String authorityDomain);

    <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> decisionRecorder(String authorityDomain);

    AuthorityOffsetCommitter offsetCommitter(String authorityDomain);

    <S, R> IdempotencyLedger<S, R> idempotencyLedger(String authorityDomain);
}
