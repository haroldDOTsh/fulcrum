package sh.harold.fulcrum.velocity.rank;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class VelocityRankServiceTest {

    @Mock
    private VelocityPlayerSessionService sessionService;
    @Mock
    private DataAPI dataAPI;
    @Mock
    private Collection collection;
    @Mock
    private Document document;
    @Mock
    private Logger logger;

    private VelocityRankService service;
    private UUID playerId;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new VelocityRankService(sessionService, dataAPI, logger);
        playerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void resolvesRankFromSession() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(playerId, UUID.randomUUID().toString(), "proxy");
        record.getRank().put("primary", "STAFF");
        record.getRank().put("all", List.of("STAFF", "DONATOR_1"));
        when(sessionService.getSession(playerId)).thenReturn(Optional.of(record));

        Rank effective = service.getEffectiveRankSync(playerId);
        Set<Rank> ranks = service.getAllRanks(playerId).join();

        assertEquals(Rank.STAFF, effective);
        assertEquals(Set.of(Rank.STAFF, Rank.DONATOR_1), ranks);
    }

    @Test
    void fallsBackToDataApiWhenSessionMissing() {
        when(sessionService.getSession(playerId)).thenReturn(Optional.empty());
        when(dataAPI.collection("players")).thenReturn(collection);
        when(collection.document(playerId.toString())).thenReturn(document);
        when(document.exists()).thenReturn(true);
        when(document.get("rankInfo.primary", null)).thenReturn("helper");
        when(document.get("rankInfo.all", null)).thenReturn(List.of("helper", "donator_1"));

        Rank primary = service.getPrimaryRankSync(playerId);
        Rank effective = service.getEffectiveRankSync(playerId);

        assertEquals(Rank.HELPER, primary);
        assertEquals(Rank.HELPER, effective);
    }

    @Test
    void providesDefaultWhenNoDataAvailable() {
        when(sessionService.getSession(playerId)).thenReturn(Optional.empty());
        when(dataAPI.collection("players")).thenReturn(collection);
        when(collection.document(playerId.toString())).thenReturn(document);
        when(document.exists()).thenReturn(false);

        Rank effective = service.getEffectiveRankSync(playerId);

        assertEquals(Rank.DEFAULT, effective);
    }

    @Test
    void mutationOperationsAreUnsupported() {
        assertInstanceOf(UnsupportedOperationException.class, assertThrows(Exception.class,
                () -> service.setPrimaryRank(playerId, Rank.STAFF, RankChangeContext.system()).join())
                .getCause());
        assertInstanceOf(UnsupportedOperationException.class, assertThrows(Exception.class,
                () -> service.addRank(playerId, Rank.STAFF, RankChangeContext.system()).join())
                .getCause());
        assertInstanceOf(UnsupportedOperationException.class, assertThrows(Exception.class,
                () -> service.resetRanks(playerId, RankChangeContext.system()).join())
                .getCause());
    }
}
