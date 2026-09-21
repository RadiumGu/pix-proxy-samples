package com.amazon.aws.pix.core.test.audit;

import com.amazon.aws.pix.core.audit.AsyncAuditWriter;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for a data-loss window that the off-request-path change introduced, found by an
 * independent review rather than by me.
 *
 * <h2>The regression</h2>
 *
 * <p>Moving the Firehose call onto a background thread put an in-memory queue between the route and
 * Firehose, and nothing flushed that queue on shutdown. The worker is a <b>daemon</b> thread, so the
 * JVM exits without running it — measured in a separate JVM as 200 records accepted and <b>0</b>
 * surviving exit. Up to the queue capacity of accepted-but-undelivered records were therefore
 * discarded on every ordinary deploy, scale-in or rollout.
 *
 * <p>It was strictly a regression: before the async writer the Firehose call was synchronous and
 * inline, so a graceful stop lost nothing already processed. The buffer bought latency and silently
 * created a standing loss window.
 *
 * <p>Two things are needed and an earlier version had neither. {@code close()} must be <em>called</em>
 * — the production route now registers each writer with the Camel context so Camel's shutdown invokes
 * it — and {@code close()} must <em>join</em> the worker, because a record already polled into the
 * worker's local batch is gone from the queue and so invisible to a drain loop on another thread.
 */
public class AsyncAuditWriterShutdownTest {

    /** close() must deliver what was accepted but not yet sent. */
    @Test
    public void closeFlushesAcceptedRecordsInsteadOfDiscardingThem() {
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final AsyncAuditWriter writer =
                new AsyncAuditWriter(10_000, delivered::addAll, r -> true);

        for (int i = 0; i < 200; i++) {
            assertTrue(writer.submit("{\"n\":" + i + "}"));
        }
        writer.close();

        assertEquals("every accepted record must survive a graceful stop", 200, delivered.size());
    }

    /**
     * The in-flight case, which is why {@code close()} joins rather than merely draining. The worker
     * has already removed the record from the queue when {@code close()} is called, so a drain loop
     * on the closing thread cannot see it — only waiting for the worker can.
     */
    @Test
    public void closeWaitsForABatchTheWorkerHasAlreadyTaken() throws Exception {
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final CountDownLatch releaseSend = new CountDownLatch(1);
        final List<String> delivered = new CopyOnWriteArrayList<>();

        final AsyncAuditWriter writer = new AsyncAuditWriter(10_000, records -> {
            sendEntered.countDown();
            // Hold the batch mid-send, exactly where a record is invisible to the queue.
            releaseSend.await(10, TimeUnit.SECONDS);
            delivered.addAll(records);
        }, r -> true);

        writer.start();
        writer.submit("{\"in\":\"flight\"}");
        assertTrue("the worker must have taken the record", sendEntered.await(10, TimeUnit.SECONDS));

        // Let the send complete once close() is waiting, so this asserts the join rather than timing.
        final Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            releaseSend.countDown();
        });
        releaser.start();

        writer.close();
        releaser.join();

        assertEquals("close() must not return before the in-flight batch is delivered",
                1, delivered.size());
    }

    /**
     * The counters must not claim safety they did not achieve. With a spool that refuses every write,
     * the record is neither delivered nor persisted, and that must be counted as LOST rather than as
     * spilled.
     */
    @Test
    public void aFailedSpoolIsCountedAsLostNotAsSpilled() {
        // Capacity 1 and no drain, so the second submit overflows.
        try (AsyncAuditWriter writer = new AsyncAuditWriter(1, b -> { }, r -> false)) {
            assertTrue(writer.submit("a"));
            assertFalse(writer.submit("b"));

            assertEquals("a refused spool must not count as spilled to safety",
                    0, writer.spilledQueueFullCount());
            assertEquals("it must count as genuinely lost", 1, writer.lostCount());
        }
    }

    /**
     * Negative control for the assertion above: with a spool that accepts, the identical overflow is
     * counted as spilled and NOT as lost. Without this, a writer that counted everything as lost
     * would satisfy the previous test.
     */
    @Test
    public void anAcceptedSpoolIsCountedAsSpilledNotAsLost() {
        try (AsyncAuditWriter writer = new AsyncAuditWriter(1, b -> { }, r -> true)) {
            assertTrue(writer.submit("a"));
            assertFalse(writer.submit("b"));

            assertEquals(1, writer.spilledQueueFullCount());
            assertEquals("a successful spool is recoverable, not lost", 0, writer.lostCount());
        }
    }

    /** A failed delivery whose spool also fails is lost, not spilled. */
    @Test
    public void aFailedBatchWithAFailedSpoolIsAlsoCountedAsLost() {
        try (AsyncAuditWriter writer = new AsyncAuditWriter(100, b -> {
            throw new IllegalStateException("firehose unavailable");
        }, r -> false)) {
            writer.submit("a");
            writer.submit("b");
            assertEquals(0, writer.drainOnce());

            assertEquals(0, writer.spilledSendFailedCount());
            assertEquals(2, writer.lostCount());
        }
    }
}
