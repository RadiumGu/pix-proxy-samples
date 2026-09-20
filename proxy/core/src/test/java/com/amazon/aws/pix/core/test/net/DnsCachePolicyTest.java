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
}
