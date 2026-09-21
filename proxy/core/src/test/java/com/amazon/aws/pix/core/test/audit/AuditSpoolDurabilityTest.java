package com.amazon.aws.pix.core.test.audit;

import com.amazon.aws.pix.core.audit.AuditSpool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the two defects an independent review found in {@link AuditSpool}: the spool was not
 * fsync'd, and its capacity check was check-then-act with concurrent callers.
 *
 * <h2>What is and is not proven here</h2>
 *
 * <p>The concurrency defect is proven <em>behaviourally</em>. The test below embeds the OLD
 * algorithm as a negative control and measures it overshooting the cap, then measures the production
 * class not overshooting under the same load. That is a real before/after on real threads.
 *
 * <p>Durability is NOT proven behaviourally, and saying otherwise would be the overclaim this
 * repository has already been burned by. Proving an fsync requires cutting power to the host; no unit
 * test can do it. What is asserted instead is that the production append opens with {@code DSYNC},
 * read from the same array the append passes, plus that the option actually works on the filesystem
 * the tests run on. Weaker than behavioural, stated as such.
 */
public class AuditSpoolDurabilityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Enough threads to lose a check-then-act race reliably. */
    private static final int THREADS = 16;

    private static final int RECORDS_PER_THREAD = 40;

    /**
     * The production append must open with DSYNC, or the spool is a page-cache buffer that a host
     * crash discards — while the whole reason the spool exists is to survive exactly that.
     */
    @Test
    public void theProductionAppendOpensWithDsync() {
        final List<StandardOpenOption> options = java.util.Arrays.asList(AuditSpool.openOptions());

        assertTrue("the spool append must use DSYNC; without it Files.write returns once the bytes "
                        + "are in the page cache and a crash loses the tail. options=" + options,
                options.contains(StandardOpenOption.DSYNC));
        assertTrue("APPEND is required so concurrent writers cannot overwrite each other's records",
                options.contains(StandardOpenOption.APPEND));
    }

    /**
     * Negative control for the assertion above: DSYNC must genuinely be honoured where the tests run,
     * otherwise the option list means nothing. Some filesystems reject it outright.
     */
    @Test
    public void dsyncIsSupportedOnTheFilesystemUnderTest() throws IOException {
        final Path probe = folder.newFile("dsync-probe").toPath();

        Files.write(probe, "x".getBytes(StandardCharsets.UTF_8), AuditSpool.openOptions());

        assertTrue("DSYNC write produced no file", Files.exists(probe));
        assertTrue("DSYNC write produced no bytes", Files.size(probe) > 0);
    }

    /**
     * The cap must hold under concurrency: every accepted record is on disk, and the file never grows
     * past {@code maxBytes}.
     */
    @Test
    public void theCapHoldsUnderConcurrentWriters() throws Exception {
        final Path spoolDir = folder.newFolder("spool").toPath();
        // A cap that lands mid-run, so acceptance and rejection both happen while threads race.
        final long cap = 4_000L;
        final AuditSpool spool = new AuditSpool(spoolDir, cap);

        final AtomicInteger accepted = new AtomicInteger();
        runConcurrently(index -> {
            if (spool.spool("{\"id\":\"" + index + "\",\"pad\":\"aaaaaaaaaaaaaaaaaaaa\"}")) {
                accepted.incrementAndGet();
            }
        });

        final long sizeOnDisk = Files.size(spool.spoolFile());
        final long lines = Files.readAllLines(spool.spoolFile()).size();

        assertTrue("the spool overshot its cap: " + sizeOnDisk + " > " + cap
                + " - the size check and the append are not atomic", sizeOnDisk <= cap);
        assertEquals("every accepted record must be a line on disk; a mismatch means records were "
                        + "counted as spooled but lost",
                accepted.get(), lines);
        assertEquals("spooledCount must agree with what is on disk", spool.spooledCount(), lines);
        assertTrue("the run must exercise BOTH outcomes or it proves nothing about the cap",
                spool.droppedCount() > 0 && accepted.get() > 0);
    }

    /**
     * The negative control that gives the test above its meaning: the OLD algorithm — read the size,
     * then append, with no lock between them — must OVERSHOOT the same cap under the same load.
     *
     * <p>Without this, "size <= cap" could pass simply because the load never approached the cap, and
     * the fix would be unverified. This is the defect reproduced, not described.
     */
    @Test
    public void theOldUnsynchronizedAlgorithmOvershootsTheCap() throws Exception {
        final Path file = folder.newFolder("control").toPath().resolve("spool.ndjson");
        Files.createDirectories(file.getParent());
        final long cap = 4_000L;

        runConcurrently(index -> {
            final byte[] line = ("{\"id\":\"" + index + "\",\"pad\":\"aaaaaaaaaaaaaaaaaaaa\"}"
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            try {
                // Exactly the shipped logic before this commit: check, then act, unsynchronised.
                final long existing = Files.exists(file) ? Files.size(file) : 0L;
                if (existing + line.length > cap) {
                    return;
                }
                Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // A control; its failures are not the subject.
            }
        });

        assertTrue("the old algorithm was expected to overshoot cap=" + cap + " but reached only "
                        + Files.size(file) + ". If this ever fails, the concurrency assertion above "
                        + "has stopped proving anything and this control must be made harsher.",
                Files.size(file) > cap);
    }

    /** Runs the action from {@link #THREADS} threads released simultaneously. */
    private void runConcurrently(final java.util.function.IntConsumer action) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            for (int thread = 0; thread < THREADS; thread++) {
                final int base = thread * RECORDS_PER_THREAD;
                pool.submit(() -> {
                    start.await();
                    for (int record = 0; record < RECORDS_PER_THREAD; record++) {
                        action.accept(base + record);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue("the concurrent writers did not finish in time",
                    pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
