package com.amazon.aws.pix.cloudhsm.proxy.camel.netty;

import org.apache.camel.component.netty.http.NettyHttpHeaderFilterStrategy;

/**
 * Header filter that stops the proxy from swallowing BCB's caching directives.
 *
 * <h2>The defect</h2>
 *
 * <p>MEASURED: {@code NettyHttpHeaderFilterStrategy} in camel-netty-http 3.4.2 carries an out-filter
 * list that includes {@code cache-control} — along with {@code pragma}, {@code warning}, {@code via},
 * {@code date} and the hop-by-hop headers. With the stock strategy, a BCB response carrying
 * {@code Cache-Control: max-age=...} reaches the caller with <b>no</b> {@code Cache-Control} at all,
 * while neighbouring headers such as {@code ETag} pass through untouched. The loss is silent.
 *
 * <h2>Why that is a correctness problem for DICT, not a performance one</h2>
 *
 * <p>The DICT API page states, under <em>Consultar Vínculo → Cache</em>, that entry-query responses
 * "podem ter suas respostas <em>cacheadas</em> no PSP, devendo seguir as diretivas contidas no header
 * {@code Cache-Control}" (RFC 7234 §5.2). A {@code getEntry} response says which account owns a Pix
 * key. The directive is the bound on how long that answer may be reused — so a proxy that removes it
 * strips the only instruction limiting how stale a key-ownership answer may get, and a stale answer
 * means initiating a payment to an account that no longer owns the key.
 *
 * <h2>What is deliberately still filtered</h2>
 *
 * <p>Only the end-to-end caching directives are unfiltered. The hop-by-hop and
 * recomputed-per-connection headers stay filtered on purpose, and un-filtering them would be actively
 * harmful rather than merely unnecessary:
 *
 * <ul>
 *   <li>{@code content-length} and {@code transfer-encoding} — the body is re-framed on the way out
 *       (and, after gzip decoding, has a different length), so forwarding the upstream values would
 *       truncate or corrupt the response.</li>
 *   <li>{@code connection}, {@code upgrade}, {@code trailer} — hop-by-hop by definition in RFC 7230;
 *       forwarding them lets an upstream connection directive act on a different connection.</li>
 *   <li>{@code host}, {@code via}, {@code date}, {@code content-type} — set or rewritten by the
 *       server side of the proxy.</li>
 * </ul>
 */
public class PixHttpHeaderFilterStrategy extends NettyHttpHeaderFilterStrategy {

    /**
     * End-to-end caching directives that must reach the caller. {@code Pragma} is included as
     * {@code Cache-Control}'s HTTP/1.0 counterpart: a client that saw one without the other could
     * reach a different caching decision than BCB intended.
     */
    private static final String[] CACHING_DIRECTIVES = {"cache-control", "pragma", "warning"};

    public PixHttpHeaderFilterStrategy() {
        super();
        for (String header : CACHING_DIRECTIVES) {
            // Lower-case because the filter set is matched case-insensitively via lower-cased keys;
            // removing "Cache-Control" would silently not match and the defect would remain.
            getOutFilter().remove(header);
            getInFilter().remove(header);
        }
    }
}
