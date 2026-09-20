package com.amazon.aws.pix.core.test.audit;

import com.amazon.aws.pix.core.audit.AsyncAuditWriter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pins the behaviour that keeps the audit write off the Pix request path without losing records. */
public class AsyncAuditWriterTest {

    @Test
    public void recordsAreDeliveredInOneBatchRatherThanOneCallEach() {
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        final List<String> spilled = new CopyOnWriteArrayList<>();

        try (AsyncAuditWriter writer =
                     new AsyncAuditWriter(100, batches::add, r -> spilled.add(r))) {
            for (int i = 0; i < 10; i++) {
                assertTrue(writer.submit("{\"n\":" + i + "}"));
            }

            assertEquals(10, writer.drainOnce());
        }

        assertEquals("ten records must cost one PutRecordBatch, not ten PutRecord calls",
                1, batches.size());
        assertEquals(10, batches.get(0).size());
        assertTrue("nothing should spill on the happy path", spilled.isEmpty());
    }

    /** The Firehose hard limit must be respected even when far more is queued. */
    @Test
    public void aBatchNeverExceedsFiveHundredRecords() {
        final List<List<String>> batches = new ArrayList<>();
        try (AsyncAuditWriter writer =
                     new AsyncAuditWriter(2000, batches::add, r -> true)) {
            for (int i = 0; i < 1200; i++) {
                writer.submit("{\"n\":" + i + "}");
            }
            while (writer.drainOnce() > 0) {
                // drain everything
            }
        }

        assertEquals("1200 records at 500 per batch", 3, batches.size());
        for (List<String> batch : batches) {
            assertTrue("batch of " + batch.size() + " exceeds the Firehose limit",
                    batch.size() <= AsyncAuditWriter.MAX_RECORDS_PER_BATCH);
        }
    }

    /**
     * The central guarantee: a full queue spills to durable storage instead of blocking the caller
     * or dropping the record. Blocking would put Firehose latency back on the Pix path; dropping
     * would lose the audit record silently.
     */
    @Test
    public void afullQueueSpillsRatherThanBlockingOrDropping() {
        final List<String> spilled = new CopyOnWriteArrayList<>();
        // Capacity 2, and nothing drains, so the third submit must spill.
        try (AsyncAuditWriter writer = new AsyncAuditWriter(2, b -> { }, spilled::add)) {

            assertTrue(writer.submit("a"));
            assertTrue(writer.submit("b"));
            assertFalse("the third record cannot be queued", writer.submit("c"));

            assertEquals(Collections.singletonList("c"), spilled);
            assertEquals(1, writer.spilledQueueFullCount());
            assertEquals("the caller was never blocked and nothing was lost",
                    2, writer.queueDepth());
        }
    }

    /** A failed batch is spilled record by record, not retried in place behind later traffic. */
    @Test
    public void aFailedBatchIsSpilledRecordByRecord() {
        final List<String> spilled = new CopyOnWriteArrayList<>();
        try (AsyncAuditWriter writer = new AsyncAuditWriter(100, b -> {
            throw new IllegalStateException("firehose unavailable");
        }, spilled::add)) {

            writer.submit("a");
            writer.submit("b");
            writer.submit("c");
            assertEquals("a failed batch delivers nothing", 0, writer.drainOnce());

            assertEquals(3, spilled.size());
            assertEquals(3, writer.spilledSendFailedCount());
            assertEquals(0, writer.deliveredCount());
        }
    }

    /**
     * Negative control for the test above. With a working sink the identical records are delivered
     * and nothing spills - so the spilling above is caused by the failure and not by the writer
     * spilling indiscriminately.
     */
    @Test
    public void theSameRecordsAreDeliveredWhenTheSinkWorks() {
        final List<String> spilled = new CopyOnWriteArrayList<>();
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        try (AsyncAuditWriter writer = new AsyncAuditWriter(100, batches::add, spilled::add)) {
            writer.submit("a");
            writer.submit("b");
            writer.submit("c");
            assertEquals(3, writer.drainOnce());
        }

        assertTrue(spilled.isEmpty());
        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).size());
    }

    /** Shutdown must flush accepted records rather than discarding them. */
    @Test
    public void closeDrainsWhatWasAlreadyAccepted() {
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        final AsyncAuditWriter writer = new AsyncAuditWriter(100, batches::add, r -> true);

        writer.submit("a");
        writer.submit("b");
        writer.close();

        assertEquals("close must not discard accepted records", 1, batches.size());
        assertEquals(2, batches.get(0).size());
    }

    /** An unbounded queue is refused outright, because it converts an outage into an OOM. */
    @Test
    public void anUnboundedQueueIsRefused() {
        try {
            new AsyncAuditWriter(0, b -> { }, r -> true);
            fail("capacity 0 must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("capacity must be positive"));
        }
    }

    /** The background thread must survive a sink that throws an Error, not die silently. */
    @Test
    public void theWorkerSurvivesAndKeepsAuditing() throws Exception {
        final List<String> spilled = new CopyOnWriteArrayList<>();
        try (AsyncAuditWriter writer = new AsyncAuditWriter(100, b -> {
            throw new OutOfMemoryError("simulated");
        }, spilled::add)) {
            writer.start();
            writer.submit("a");

            for (int i = 0; i < 50 && spilled.isEmpty(); i++) {
                Thread.sleep(40);
            }
            assertFalse("a dead writer would stop auditing everything, silently",
                    spilled.isEmpty());
        }
    }
}
