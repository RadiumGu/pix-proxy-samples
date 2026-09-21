package com.amazon.aws.pix.core.test.net;

import com.amazon.aws.pix.core.net.DnsCachePolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.Security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pins the DNS cache bound the Manual de Segurança do Pix requires. */
public class DnsCachePolicyTest {

    private String savedTtl;
    private String savedNegativeTtl;

    @Before
    public void saveProperties() {
        savedTtl = Security.getProperty(DnsCachePolicy.TTL_PROPERTY);
        savedNegativeTtl = Security.getProperty(DnsCachePolicy.NEGATIVE_TTL_PROPERTY);
    }

    /** These are JVM-global, so leaving them changed would leak into other tests. */
    @After
    public void restoreProperties() {
        if (savedTtl != null) {
            Security.setProperty(DnsCachePolicy.TTL_PROPERTY, savedTtl);
        }
        if (savedNegativeTtl != null) {
            Security.setProperty(DnsCachePolicy.NEGATIVE_TTL_PROPERTY, savedNegativeTtl);
        }
    }

    /** The value must land on the java.security property, which is the one that governs. */
    @Test
    public void applySetsTheSecurityPropertyThatActuallyGoverns() {
        DnsCachePolicy.apply();

        assertEquals(Integer.toString(DnsCachePolicy.DEFAULT_TTL_SECONDS),
                Security.getProperty(DnsCachePolicy.TTL_PROPERTY));
        assertEquals(Integer.toString(DnsCachePolicy.DEFAULT_NEGATIVE_TTL_SECONDS),
                Security.getProperty(DnsCachePolicy.NEGATIVE_TTL_PROPERTY));
    }

    /**
     * The exact failure the Manual warns about: "cache forever" must be refused, not honoured.
     * A process that caches a successful lookup for its whole life keeps dialling an address BCB
     * has stopped serving.
     */
    @Test
    public void cacheForeverIsRefused() {
        try {
            DnsCachePolicy.apply(-1, 1);
            fail("-1 means cache forever and must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cache forever"));
        }
    }

    /**
     * Negative control, and the reason the test above carries information: starting from the
     * dangerous value, apply() must actually replace it rather than leaving it in place.
     */
    @Test
    public void applyOverwritesAnAlreadyDangerousValue() {
        Security.setProperty(DnsCachePolicy.TTL_PROPERTY, "-1");
        assertEquals("-1", Security.getProperty(DnsCachePolicy.TTL_PROPERTY));

        DnsCachePolicy.apply();

        assertNotEquals("cache-forever must not survive apply()",
                "-1", Security.getProperty(DnsCachePolicy.TTL_PROPERTY));
        assertEquals(Integer.toString(DnsCachePolicy.DEFAULT_TTL_SECONDS),
                DnsCachePolicy.effectiveTtl());
    }

    /** Zero is legitimate - never cache - and must be distinguishable from "cache forever". */
    @Test
    public void zeroIsAcceptedAndMeansNeverCache() {
        DnsCachePolicy.apply(0, 0);

        assertEquals("0", Security.getProperty(DnsCachePolicy.TTL_PROPERTY));
    }

    /** The returned description must state what was applied, for an honest startup log. */
    @Test
    public void theDescriptionNamesThePropertyAndTheValue() {
        final String description = DnsCachePolicy.apply(17, 2);

        assertTrue(description, description.contains(DnsCachePolicy.TTL_PROPERTY));
        assertTrue(description, description.contains("17"));
    }

    /**
     * The defect this class previously had, and the reason every other test here was too weak: all of
     * them asserted the {@code Security} property write, which always succeeds. The property is only
     * <em>read</em> once, in {@code sun.net.InetAddressCachePolicy}'s static initializer, so a write
     * after the first name resolution changes nothing — and the method used to report success anyway.
     *
     * <p>MEASURED: with a lookup performed first, the property reads 7 while the effective policy
     * stays 30. This test forces that ordering and requires the description to SAY it was ineffective
     * rather than claim success.
     */
    @Test
    public void applyReportsHonestlyWhenItRanTooLateToTakeEffect() throws Exception {
        // Force the policy class to initialise, exactly as any startup DNS lookup would.
        try {
            java.net.InetAddress.getByName("localhost");
        } catch (Exception ignored) {
            // Resolution failure still loads the class, which is all this needs.
        }

        final Integer effective = DnsCachePolicy.effectivePolicySeconds();
        final String description = DnsCachePolicy.apply(7, 1);

        if (effective == null) {
            // sun.net is not exported here, so the method cannot verify - it must say so rather than
            // confirm. Production adds --add-exports for exactly this reason.
            assertTrue(description, description.contains("UNVERIFIED"));
        } else if (effective != 7) {
            assertTrue("an ineffective call must not report success: " + description,
                    description.contains("INEFFECTIVE"));
            assertTrue(description, description.contains(DnsCachePolicy.LEGACY_SYSTEM_PROPERTY));
        } else {
            assertTrue(description, description.contains("confirmed"));
        }
    }

    /** The verification helper must never throw, whatever the JVM allows. */
    @Test
    public void theEffectivePolicyReaderNeverThrows() {
        DnsCachePolicy.effectivePolicySeconds();
    }
}
