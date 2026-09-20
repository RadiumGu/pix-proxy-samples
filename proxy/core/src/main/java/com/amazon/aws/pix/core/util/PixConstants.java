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

    /**
     * Fourth value for {@link #PIX_HEADER_SIGNATURE_VALID}.
     * <p>
     * A body the proxy could not decode is not a bad signature either - it is a transport-encoding
     * problem. BCB's API page recommends that clients send {@code Accept-Encoding: gzip}, and the
     * proxy forwards client headers transparently, so a compressed response is the expected case
     * rather than an exotic one. Recording it as {@code "false"} would put a compression fault in
     * the signature-mismatch bucket, which is the same conflation
     * {@link #SIGNATURE_VALID_CERTIFICATE_ERROR} exists to prevent.
     * <p>
     * Like that value, this one needs no Glue schema change: the column
     * {@code response_signature_valid} is typed STRING.
     */
    String SIGNATURE_VALID_CONTENT_ENCODING_ERROR = "content-encoding-error";

    /**
     * Exchange property set when the response body could not be decoded.
     * <p>
     * It is a property rather than an exception so the exchange survives to the audit write; see
     * {@code DecodeResponseProcessor}. {@code VerifyResponseProcessor} reads it and skips
     * verification, because verifying a body we failed to decode can only produce a misleading
     * signature verdict.
     */
    String PIX_CONTENT_ENCODING_ERROR = "pix-content-encoding-error";

}
