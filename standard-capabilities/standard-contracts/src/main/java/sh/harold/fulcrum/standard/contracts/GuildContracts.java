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

public final class GuildContracts {
    public static final ContractName CONTRACT = new ContractName("standard.guild.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.guild";
    public static final String EVENT_TOPIC = "evt.standard.guild";
    public static final String STATE_TOPIC = "state.standard.guild";
    public static final String RESPONSE_TOPIC = "rsp.standard.guild";
    public static final String ROSTER_PROJECTION = "standard_guild_roster";
    public static final String SUBJECT_INDEX_PROJECTION = "standard_guild_subject_index";

    private GuildContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("create-guild"),
                        "CreateGuild",
                        List.of(
                                new FieldDeclaration("guildId", FieldType.STRING),
                                new FieldDeclaration("ownerSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("createdAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("guild-created"),
                        "GuildCreated",
                        List.of(
                                new FieldDeclaration("guildId", FieldType.STRING),
                                new FieldDeclaration("ownerSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "GuildRosterSnapshot",
                        List.of(
                                new FieldDeclaration("guildId", FieldType.STRING),
                                new FieldDeclaration("ownerSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "guildRosterProjection",
                                ROSTER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("guildId", FieldType.STRING),
                                        new FieldDeclaration("ownerSubjectId", FieldType.STRING),
                                        new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                        new FieldDeclaration("displayName", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "guildSubjectIndexProjection",
                                SUBJECT_INDEX_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("guildId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-guild-client"), List.of("standard-guild-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-guild-authority"), List.of("standard-guild-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-guild-authority"), List.of("standard-guild-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-guild-authority"), List.of("standard-guild-client"))));
    }
}
