package com.amazon.aws.pix.core.test.audit;

import com.amazon.aws.pix.core.audit.AuditSpool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link AuditSpool}, the durable fallback for audit records Firehose refused.
 *
 * <p>The behaviour that matters most is the behaviour under failure, so most of these tests are
 * about what happens when things go wrong: the spool must never throw (it runs on a path already
 * handling a failure), must stay bounded (an unbounded spool turns a sink outage into a disk-full
 * outage and takes down payments to protect an audit copy), and must keep a count an operator can
 * alarm on.
 */
public class AuditSpoolTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String RECORD =
            "{\"request_body\":\"<GetEntryRequest/>\",\"response_signature_valid\":\"true\"}";

    private AuditSpool spoolIn(Path dir) {
        return new AuditSpool(dir);
    }

    @Test
    public void aRecordIsPersistedAsOneNdjsonLine() throws Exception {
        final AuditSpool spool = spoolIn(folder.getRoot().toPath());

        assertTrue(spool.spool(RECORD));

        final List<String> lines = Files.readAllLines(spool.spoolFile(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals(RECORD, lines.get(0));
        assertEquals(1, spool.spooledCount());
        assertEquals(0, spool.droppedCount());
    }

    @Test
    public void recordsAppendRatherThanOverwrite() throws Exception {
        final AuditSpool spool = spoolIn(folder.getRoot().toPath());

        spool.spool("{\"n\":1}");
        spool.spool("{\"n\":2}");
        spool.spool("{\"n\":3}");

        assertEquals(3, Files.readAllLines(spool.spoolFile()).size());
        assertEquals(3, spool.spooledCount());
    }

    @Test
    public void embeddedNewlinesCannotBreakTheOneRecordPerLineInvariant() throws Exception {
        final AuditSpool spool = spoolIn(folder.getRoot().toPath());

        // A Pix XML body can legitimately contain newlines; NDJSON would otherwise be corrupted and
        // one record would be read as several.
        spool.spool("{\"body\":\"<a>\nline2\nline3</a>\"}");

        assertEquals("a record containing newlines must still occupy exactly one line",
                1, Files.readAllLines(spool.spoolFile()).size());
    }

    @Test
    public void theSpoolDirectoryIsCreatedIfAbsent() {
        final Path nested = folder.getRoot().toPath().resolve("does/not/exist/yet");
        final AuditSpool spool = new AuditSpool(nested);

        assertTrue("the spool must create its own directory rather than losing the record",
                spool.spool(RECORD));
        assertTrue(Files.exists(spool.spoolFile()));
    }

    // ---------------------------------------------------------------- bounded

    @Test
    public void theSpoolStopsAcceptingOnceItsCeilingIsReached() {
        // Ceiling small enough that the second record cannot fit.
        final AuditSpool spool = new AuditSpool(folder.getRoot().toPath(), RECORD.length() + 2);

        assertTrue("the first record fits", spool.spool(RECORD));
        assertFalse("the second must be refused rather than growing without bound",
                spool.spool(RECORD));
        assertEquals(1, spool.spooledCount());
        assertEquals("a refused record is lost audit data and must be counted",
                1, spool.droppedCount());
    }

    @Test
    public void aRejectedCeilingIsReportedRatherThanSilentlyAccepted() {
        try {
            new AuditSpool(folder.getRoot().toPath(), 0);
            fail("a non-positive ceiling must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxBytes"));
        }
        try {
            new AuditSpool(null);
            fail("a null directory must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("directory"));
        }
    }

    // ---------------------------------------------------------------- never throws

    @Test
    public void anUnwritableLocationIsCountedAsDroppedRatherThanThrowing() throws Exception {
        // A file where the directory should be: every write attempt must fail.
        final Path blocked = folder.newFile("blocked").toPath().resolve("under-a-file");
        final AuditSpool spool = new AuditSpool(blocked);

        assertFalse(spool.spool(RECORD));
        assertEquals("the failure must be counted, not thrown", 1, spool.droppedCount());
        assertEquals(0, spool.spooledCount());
    }

    @Test
    public void blankAndNullRecordsAreIgnoredWithoutCountingAsDrops() {
        final AuditSpool spool = spoolIn(folder.getRoot().toPath());

        assertFalse(spool.spool(null));
        assertFalse(spool.spool(""));
        assertFalse(spool.spool("   "));
        assertEquals(0, spool.spooledCount());
        assertEquals("nothing was lost, so nothing should be reported as lost",
                0, spool.droppedCount());
    }

    @Test
    public void statusNamesTheFileAndBothCountersSoItCanBeAlarmedOn() {
        final AuditSpool spool = spoolIn(folder.getRoot().toPath());
        spool.spool(RECORD);

        final String status = spool.status();
        assertTrue(status.contains("spooled=1"));
        assertTrue(status.contains("dropped=0"));
        assertTrue(status.contains("pix-audit-spool.ndjson"));
    }
}
