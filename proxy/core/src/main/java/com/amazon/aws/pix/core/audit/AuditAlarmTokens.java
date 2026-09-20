package com.amazon.aws.pix.core.audit;

/**
 * Stable tokens that CloudWatch Logs metric filters match on.
 *
 * <p>These exist so alarms do not pattern-match prose. A log message gets reworded during ordinary
 * maintenance, and a metric filter that matched the old wording then silently stops firing — the
 * alarm goes quiet, which reads identically to "nothing is wrong". Keeping the machine-readable
 * token separate from the human sentence means the message can be improved freely while the alarm
 * keeps working.
 *
 * <p><b>Changing one of these strings breaks a deployed alarm.</b> They are matched by metric
 * filters defined in README-CloudHSM.md, so treat them as a published interface rather than as log
 * text.
 */
public final class AuditAlarmTokens {

    /** An audit record could not be delivered and went to the local spool instead. */
    public static final String SPOOLED = "PIX_AUDIT_SPOOLED";

    /** The off-path queue was full, so delivery is not keeping up with traffic. */
    public static final String QUEUE_FULL = "PIX_AUDIT_QUEUE_FULL";

    /** An exchange produced no audit record at all - it failed before the request was captured. */
    public static final String NO_RECORD = "PIX_AUDIT_NO_RECORD";

    /** The spool itself could not be written: the record is now genuinely lost. */
    public static final String SPOOL_WRITE_FAILED = "PIX_AUDIT_SPOOL_WRITE_FAILED";

    private AuditAlarmTokens() {
    }
}
