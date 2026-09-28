package com.vexsoftware.votifier.support.forwarding.proxy.client;

import com.vexsoftware.votifier.model.Vote;

/** A snapshot of a vote for one authenticated forwarding exchange. */
public final class VoteRequest {
    private final String challenge;
    private final Vote vote;

    public VoteRequest(String challenge, Vote vote) {
        this.challenge = challenge;
        this.vote = new Vote(vote);
    }

    public String getChallenge() {
        return challenge;
    }

    public Vote getVote() {
        return new Vote(vote);
    }

    @Override
    public String toString() {
        return "VoteRequest{" +
                "challenge='" + challenge + '\'' +
                ", vote=" + vote +
                '}';
    }
}
