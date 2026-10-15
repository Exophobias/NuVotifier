package com.vexsoftware.votifier.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VotifierEventTest {
    @Test
    void constructorOverloadsRetainSharedVoteAndDeclaredDispatchMode() {
        Vote vote = new Vote("service", "player", "localhost", "1");
        VotifierEvent legacy = new VotifierEvent(vote);
        assertSame(vote, legacy.getVote());
        assertFalse(legacy.isAsynchronous());

        for (boolean async : new boolean[]{false, true}) {
            VotifierEvent event = new VotifierEvent(vote, async);
            assertSame(vote, event.getVote());
            assertEquals(async, event.isAsynchronous());
            event.getVote().setUsername("listener");
            assertEquals("listener", vote.getUsername());
            assertSame(legacy.getHandlers(), event.getHandlers());
        }
    }
}
