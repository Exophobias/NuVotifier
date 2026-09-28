package com.vexsoftware.votifier;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Shared fixed-rate diagnostics so hostile network traffic cannot flood server logs. */
final class VoteErrorReporter {
    private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final Logger logger;
    private final LongSupplier clock;
    private boolean reported;
    private long nextWarning;
    private long suppressed;

    VoteErrorReporter(Logger logger) {
        this(logger, System::nanoTime);
    }

    VoteErrorReporter(Logger logger, LongSupplier clock) {
        this.logger = logger;
        this.clock = clock;
    }

    synchronized void report(boolean voteCompleted) {
        long now = clock.getAsLong();
        if (reported && now - nextWarning < 0) {
            if (suppressed < Long.MAX_VALUE) {
                suppressed++;
            }
            return;
        }
        String message = voteCompleted
                ? "A vote was processed but its response could not be completed."
                : "Unable to authenticate or process an incoming vote.";
        if (suppressed > 0) {
            message += " Suppressed " + suppressed + " additional vote-processing warnings.";
        }
        reported = true;
        nextWarning = now + INTERVAL_NANOS;
        suppressed = 0;
        logger.warning(message);
    }
}
