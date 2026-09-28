package com.vexsoftware.votifier;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

class VoteErrorReporterTest {
    @Test
    void errorsAreRateLimitedAndSummarizedWithoutUntrustedMessages() {
        Logger logger = mock(Logger.class);
        AtomicLong nanos = new AtomicLong(-500);
        VoteErrorReporter reporter = new VoteErrorReporter(logger, nanos::get);
        reporter.report(false);
        for (int i = 0; i < 1000; i++) {
            reporter.report(false);
        }
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(29));
        reporter.report(true);
        verify(logger, times(1)).warning(anyString());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        reporter.report(false);
        verify(logger).warning("Unable to authenticate or process an incoming vote. Suppressed 1001 additional vote-processing warnings.");
        verify(logger, times(2)).warning(anyString());
    }
}
