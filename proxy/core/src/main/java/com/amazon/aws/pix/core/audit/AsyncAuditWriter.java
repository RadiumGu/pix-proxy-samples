package com.amazon.aws.pix.core.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Takes the audit write off the request path: records are handed to a bounded queue and delivered in
 * batches by a background thread.
 *
 * <h2>Why bounded, and what happens when it fills</h2>
 *
 * <p>The queue is bounded on purpose and the capacity is the whole design. An unbounded queue turns
 * a Firehose outage into heap exhaustion, which takes down the payment proxy — trading a lost audit
 * record for a total outage, which is the wrong way round. So the queue has a ceiling.
 *
 * <p>When it is full there are only three options and two of them are unacceptable. Blocking the
 * caller puts Firehose latency back onto the Pix path, which is what this class exists to remove.
 * Dropping silently loses an audit record with no trace, which is the compliance failure the spool
 * was introduced to prevent. So the third is taken: the record is written to the durable spool
 * immediately and the caller is never delayed. A full queue therefore degrades to the previous
 * behaviour rather than to data loss.
 *
 * <h2>Batching</h2>
 *
 * <p>Firehose accepts up to 500 records and 4 MB per {@code PutRecordBatch}, so a batch is capped by
 * both. Batching is not only cheaper: it is what makes a single background thread able to keep up
 * with a proxy that is signing continuously.
 *
 * <p>Deliberately free of any AWS dependency — the caller supplies the delivery and the spill — so
 * this is testable without Firehose, which matters because the module that owns the Firehose client
 * is built with {@code -skipTests} in CI.
 */
public class AsyncAuditWriter implements AutoCloseable {

    /** Firehose PutRecordBatch hard limit. */
    public static final int MAX_RECORDS_PER_BATCH = 500;

    /** Firehose PutRecordBatch hard limit, in bytes. */
    public static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;

    /** Delivers one batch; throwing means the batch was not delivered. */
    public interface BatchSink {
        void send(List<String> records) throws Exception;
    }

    /** Receives records that could not be queued or could not be delivered. */
    public interface SpillSink {
        boolean spill(String record);
    }

    private final BlockingQueue<String> queue;
    private final BatchSink sink;
    private final SpillSink spill;
    private final int maxRecordsPerBatch;

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong spilledQueueFull = new AtomicLong();
    private final AtomicLong spilledSendFailed = new AtomicLong();
    /** Records neither delivered NOR spooled. The only genuinely unrecoverable outcome. */
    private final AtomicLong lost = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;

    public AsyncAuditWriter(final int capacity, final BatchSink sink, final SpillSink spill) {
        this(capacity, MAX_RECORDS_PER_BATCH, sink, spill);
    }

    public AsyncAuditWriter(final int capacity, final int maxRecordsPerBatch,
            final BatchSink sink, final SpillSink spill) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive; an unbounded queue "
                    + "would turn a Firehose outage into heap exhaustion");
        }
        if (sink == null || spill == null) {
            throw new IllegalArgumentException("sink and spill are both required");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink = sink;
        this.spill = spill;
        this.maxRecordsPerBatch = Math.min(maxRecordsPerBatch, MAX_RECORDS_PER_BATCH);
    }

    /**
     * Accepts one record. Never blocks and never throws, so it is safe on the request path.
     *
     * @return {@code true} if queued, {@code false} if the queue was full and the record was
     *         spilled to durable storage instead
     */
    public boolean submit(final String record) {
        if (record == null) {
            return true;
        }
        if (queue.offer(record)) {
            enqueued.incrementAndGet();
            return true;
        }
        // Honour spill()'s return. Counting a record as "spilled to safety" when the spool write
        // actually failed made this metric over-report safety - the same class of lie as the
        // Error-swallowing bug fixed earlier in this class, and it defeats reconciling the counter
        // against records genuinely persisted. AuditSpool logs PIX_AUDIT_SPOOL_WRITE_FAILED for the
        // unrecoverable case.
        if (spill.spill(record)) {
            spilledQueueFull.incrementAndGet();
        } else {
            lost.incrementAndGet();
        }
        return false;
    }

    /** Starts the background drain. Idempotent. */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::runLoop, "pix-audit-writer");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                final String first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    drainStartingWith(first);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // The writer must never die: a dead writer silently stops auditing everything.
                // No counter is incremented here - drainStartingWith owns spilling and counting,
                // and incrementing a "spilled" counter at this level once claimed records were
                // safe when they had in fact been lost.
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Drains and delivers whatever is queued, once. Exposed so tests can exercise delivery
     * deterministically instead of racing a background thread.
     *
     * @return the number of records delivered
     */
    public int drainOnce() {
        final String first = queue.poll();
        if (first == null) {
            return 0;
        }
        return drainStartingWith(first);
    }

    private int drainStartingWith(final String first) {
        final List<String> batch = new ArrayList<>();
        batch.add(first);
        int bytes = first.length();

        while (batch.size() < maxRecordsPerBatch && bytes < MAX_BATCH_BYTES) {
            final String next = queue.peek();
            if (next == null || bytes + next.length() > MAX_BATCH_BYTES) {
                break;
            }
            queue.poll();
            batch.add(next);
            bytes += next.length();
        }

        try {
            sink.send(Collections.unmodifiableList(batch));
            delivered.addAndGet(batch.size());
            return batch.size();
        } catch (Throwable t) {
            // Throwable, not Exception, and that distinction was a real bug caught by a test. An
            // Error from the sink - an OutOfMemoryError during an allocation spike, a
            // NoClassDefFoundError from a half-initialised AWS client - used to escape to the
            // worker loop, which incremented the "spilled" counter WITHOUT spilling anything. The
            // records were lost and the metric claimed they were safe, which is worse than either
            // failure alone. Catching an Error to write a few lines to disk is deliberate: the
            // alternative is silently losing audit records.
            //
            // A failed batch is spilled record by record rather than retried in place: retrying
            // here would stall every later record behind a broken stream, and the spool is the
            // durable path that already exists for exactly this.
            for (String record : batch) {
                if (spill.spill(record)) {
                    spilledSendFailed.incrementAndGet();
                } else {
                    lost.incrementAndGet();
                }
            }
            return 0;
        }
    }

    /** Current queue depth; a sustained non-zero value means delivery is not keeping up. */
    public int queueDepth() {
        return queue.size();
    }

    public long enqueuedCount() {
        return enqueued.get();
    }

    public long deliveredCount() {
        return delivered.get();
    }

    /** Records spilled because the queue was full - each one is a compliance signal to alarm on. */
    public long spilledQueueFullCount() {
        return spilledQueueFull.get();
    }

    /** Records spilled because delivery failed - likewise alarm-worthy. */
    public long spilledSendFailedCount() {
        return spilledSendFailed.get();
    }

    /**
     * Records neither delivered nor persisted — genuinely lost.
     *
     * <p>Kept separate from the spilled counters deliberately. "Spilled" is recoverable if the spool
     * is shipped; this is not. Previously both paths incremented a spilled counter without checking
     * whether the spool write had actually succeeded, so the metric reported records as safe when
     * they were gone — the same class of lie as the Error-swallowing bug fixed earlier in this class,
     * and it made the counters impossible to reconcile against records genuinely persisted.
     */
    public long lostCount() {
        return lost.get();
    }

    /**
     * Stops the worker after draining what is queued, so a container shutdown does not discard
     * records that were accepted but not yet delivered.
     */
    /**
     * Stops the worker and flushes what is queued.
     *
     * <p>Two things here are load-bearing and an earlier version had neither right.
     *
     * <p><b>It must be called at all.</b> The worker is a daemon thread, so the JVM exits without
     * running it — MEASURED in a separate JVM: 200 records accepted, <b>0</b> survived exit. Queued
     * records were therefore discarded on every ordinary container stop. The caller is responsible
     * for invoking this; the production route registers each writer with the Camel context so that
     * Camel's own shutdown calls it.
     *
     * <p><b>It must join the worker.</b> Interrupting and then draining from the calling thread is not
     * enough: a record the worker has already polled into its local batch is gone from the queue and
     * therefore invisible to the drain loop, so it would be lost mid-send. The join is bounded, since
     * a shutdown that hangs forever is its own outage.
     */
    @Override
    public void close() {
        running.set(false);

        if (worker != null) {
            // NOT interrupted first: interrupting mid-PutRecordBatch would abort an in-flight
            // delivery that was about to succeed. The loop exits within its 200ms poll timeout.
            try {
                worker.join(CLOSE_JOIN_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                // Escalate only after the grace period, then give the batch a moment to spill.
                worker.interrupt();
                try {
                    worker.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Anything still queued is delivered (or spilled) on this thread.
        while (drainOnce() > 0) {
            // keep going until the queue is empty
        }
    }

    /** Bounded: a shutdown that never returns is its own outage. */
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 10_000L;
}
