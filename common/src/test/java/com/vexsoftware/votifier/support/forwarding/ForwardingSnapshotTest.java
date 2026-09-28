package com.vexsoftware.votifier.support.forwarding;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import com.vexsoftware.votifier.support.forwarding.proxy.client.VoteRequest;
import com.vexsoftware.votifier.support.forwarding.proxy.client.VotifierProtocol2HandshakeHandler;
import com.vexsoftware.votifier.support.forwarding.proxy.client.VotifierResponseHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ForwardingSnapshotTest {
    @Test
    void editsToTheSourceNamesCannotChangeAnExistingServerPolicy() {
        List<String> names = new ArrayList<>(List.of("allowed"));
        ServerFilter whitelist = new ServerFilter(names, true);
        ServerFilter blacklist = new ServerFilter(names, false);
        names.clear();
        names.add("other");
        assertTrue(whitelist.isAllowed("allowed"));
        assertFalse(whitelist.isAllowed("other"));
        assertFalse(blacklist.isAllowed("allowed"));
        assertTrue(blacklist.isAllowed("other"));
    }

    @Test
    void queuedRequestCannotBeChangedThroughItsInputOrReturnedVote() {
        Vote source = new Vote("Service", "original_user", "127.0.0.1", "000123", new byte[]{1, 2, 3});
        VoteRequest request = new VoteRequest("challenge", source);
        source.setUsername("changed_user");
        source.setServiceName("Other service");
        Vote exposed = request.getVote();
        exposed.setUsername("another_user");
        assertEquals("original_user", request.getVote().getUsername());
        assertEquals("Service", request.getVote().getServiceName());
        assertEquals("000123", request.getVote().getTimeStamp());
        assertArrayEquals(new byte[]{1, 2, 3}, request.getVote().getAdditionalData());
    }

    @Test
    void deferredHandshakeSendsTheVoteThatWasQueuedBeforeListenerMutations() {
        Vote source = new Vote("Service", "original_user", "127.0.0.1", "123");
        EmbeddedChannel channel = new EmbeddedChannel(new VotifierProtocol2HandshakeHandler(source,
                mock(VotifierResponseHandler.class), mock(VotifierPlugin.class)));
        source.setUsername("changed_user");
        try {
            assertFalse(channel.writeInbound("VOTIFIER 2 challenge"));
            VoteRequest request = channel.readOutbound();
            assertNotNull(request);
            assertEquals("challenge", request.getChallenge());
            assertEquals("original_user", request.getVote().getUsername());
            assertEquals("Service", request.getVote().getServiceName());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
