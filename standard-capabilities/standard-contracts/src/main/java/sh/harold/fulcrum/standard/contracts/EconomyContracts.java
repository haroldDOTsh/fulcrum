package sh.harold.fulcrum.standard.contracts;

import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.SnapshotDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import java.util.List;
import java.util.Optional;

public final class EconomyContracts {
    public static final ContractName CONTRACT = new ContractName("standard.economy.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.economy";
    public static final String EVENT_TOPIC = "evt.standard.economy";
    public static final String STATE_TOPIC = "state.standard.economy";
    public static final String RESPONSE_TOPIC = "rsp.standard.economy";
    public static final String BALANCE_PROJECTION = "standard_economy_balance";
    public static final String LEDGER_PROJECTION = "standard_economy_ledger";

    private EconomyContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("post-ledger-entry"),
                        "PostLedgerEntry",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("currencyKey", FieldType.STRING),
                                new FieldDeclaration("deltaMinorUnits", FieldType.LONG),
                                new FieldDeclaration("reason", FieldType.STRING),
                                new FieldDeclaration("occurredAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("ledger-entry-posted"),
                        "EconomyLedgerEntryRecorded",
                        List.of(
                                new FieldDeclaration("entryId", FieldType.STRING),
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("currencyKey", FieldType.STRING),
                                new FieldDeclaration("deltaMinorUnits", FieldType.LONG),
                                new FieldDeclaration("resultingBalanceMinorUnits", FieldType.LONG),
                                new FieldDeclaration("reason", FieldType.STRING),
                                new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "EconomyBalanceSnapshot",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("currencyKey", FieldType.STRING),
                                new FieldDeclaration("balanceMinorUnits", FieldType.LONG),
                                new FieldDeclaration("lastEntryId", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "economyBalanceProjection",
                                BALANCE_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("balanceMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("lastEntryId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "economyLedgerProjection",
                                LEDGER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("entryId", FieldType.STRING),
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("deltaMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("resultingBalanceMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("reason", FieldType.STRING),
                                        new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-economy-client"), List.of("standard-economy-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-economy-authority"), List.of("standard-economy-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-economy-authority"), List.of("standard-economy-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-economy-authority"), List.of("standard-economy-client"))));
    }
}
