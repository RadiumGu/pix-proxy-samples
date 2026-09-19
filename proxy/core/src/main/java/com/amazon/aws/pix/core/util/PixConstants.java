package com.amazon.aws.pix.core.util;

public interface PixConstants {

    String PIX_HEADERS = "pix-headers";
    String PIX_HEADER_PREFIX = "pix-";
    String PIX_HEADER_SIGNATURE_VALID = "pix-signature-valid";

    /**
     * Third value for {@link #PIX_HEADER_SIGNATURE_VALID}, beside {@code "true"} and
     * {@code "false"}.
     * <p>
     * A trusted certificate outside its validity period is neither a good signature nor a bad
     * one - it is a certificate rotation problem, and recording it as {@code "false"} would put
     * it back in the bucket the fix for upstream issue #19 pulled it out of. The Glue column
     * {@code response_signature_valid} is typed STRING, so a third value needs no schema change.
     */
    String SIGNATURE_VALID_CERTIFICATE_ERROR = "certificate-validity-error";

}
