package com.amazon.aws.pix.core.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only local fallback for audit records the primary sink refused.
 *
 * <p><b>Why this exists.</b> The audit record is the artefact a compliance reader asks for, and a
 * review found it could be lost silently in three independent ways: a Firehose {@code PutRecord}
 * failure was swallowed with only a log line (and the alarm that log line tells you to build did not
 * exist anywhere in the repository); a transport or TLS failure on the BCB leg aborted the exchange
 * before the audit step ran at all; and a signing failure threw before the record was even captured.
 * The first of those is the one this class addresses directly — it gives the swallowed record
 * somewhere durable to land instead of vanishing.
 *
 * <p><b>Deliberately a file, not a queue.</b> A second network sink (SQS) fails in the same
 * conditions that take Firehose down — the outage that makes the record most valuable is exactly
 * when a network fallback is least likely to work. A local append is available whenever the process
 * is. The tradeoff is explicit and must be understood: <b>a spool on a container filesystem dies
 * with the task</b>, so this is a bridge across a transient sink failure, NOT durable storage. For
 * production, point {@link #spoolDirectory} at a mounted volume and ship it (a log agent tailing the
 * file is enough), and alarm on {@link #spooledCount()} being non-zero.
 *
 * <p><b>Bounded on purpose.</b> An unbounded spool turns a sink outage into a disk-full outage,
 * which would take down payment processing to protect an audit copy — the wrong trade. Once
 * {@link #maxBytes} is reached the spool stops accepting and reports the drop, so the failure stays
 * where it can be seen instead of cascading.
 *
 * <p>This class is free of AWS SDK and Camel types so it can be unit-tested in {@code proxy/core},
 * one of the only two modules whose tests execute in CI.
 */
@lombok.extern.slf4j.Slf4j
public class AuditSpool {

    /** Default ceiling. Small enough not to threaten a container's disk, large enough to bridge a sink blip. */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final Path spoolFile;
    private final long maxBytes;
    private final AtomicLong spooled = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public AuditSpool(Path spoolDirectory) {
        this(spoolDirectory, DEFAULT_MAX_BYTES);
    }

    public AuditSpool(Path spoolDirectory, long maxBytes) {
        if (spoolDirectory == null) {
            throw new IllegalArgumentException("spool directory is required");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, got " + maxBytes);
        }
        this.spoolFile = spoolDirectory.resolve("pix-audit-spool.ndjson");
        this.maxBytes = maxBytes;
    }

    /** Where records land. Exposed so operators and tests can find the file. */
    public Path spoolFile() {
        return spoolFile;
    }

    /** Records written to the spool since start. Alarm on this being non-zero. */
    public long spooledCount() {
        return spooled.get();
    }

    /** Records the spool itself could not keep. Any non-zero value is lost audit data. */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Appends one audit record as a line of NDJSON.
     *
     * <p>Never throws. This is called on a path that is already handling a failure, and letting a
     * second failure escape would damage the transaction the audit record is about — the exact
     * inversion the fix for upstream issue #18 removed.
     *
     * @param auditJson the serialised audit record; {@code null} or blank is ignored
     * @return {@code true} if the record was persisted
     */
    public boolean spool(String auditJson) {
        if (auditJson == null || auditJson.trim().isEmpty()) {
            return false;
        }
        final byte[] line = (auditJson.replace('\n', ' ') + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (spoolFile.getParent() != null) {
                Files.createDirectories(spoolFile.getParent());
            }
            final long existing = Files.exists(spoolFile) ? Files.size(spoolFile) : 0L;
            if (existing + line.length > maxBytes) {
                dropped.incrementAndGet();
                // The record is now genuinely lost - not delivered and not spooled. Previously this
                // returned false in silence, so the single worst audit outcome was the quietest one
                // in the logs. It carries a stable alarm token for that reason.
                log.error("{} audit record LOST: the spool is full ({} bytes, cap {}) so the record "
                                + "was neither delivered nor persisted. Ship and truncate the spool.",
                        AuditAlarmTokens.SPOOL_WRITE_FAILED, existing, maxBytes);
                return false;
            }
            Files.write(spoolFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            spooled.incrementAndGet();
            return true;
        } catch (IOException | RuntimeException e) {
            dropped.incrementAndGet();
            log.error("{} audit record LOST: writing to the spool at {} failed, so the record was "
                    + "neither delivered nor persisted.", AuditAlarmTokens.SPOOL_WRITE_FAILED,
                    spoolFile, e);
            return false;
        }
    }

    /**
     * A one-line operational summary, for the log statement that reports a sink failure.
     */
    public String status() {
        return "spool=" + spoolFile + " spooled=" + spooled.get() + " dropped=" + dropped.get()
                + " maxBytes=" + maxBytes + " at=" + Instant.now();
    }
}
