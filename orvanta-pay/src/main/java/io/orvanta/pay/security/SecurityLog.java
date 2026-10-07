package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The record of security-relevant events: sign-ins and their failures, lock-outs, sign-outs,
 * refused permissions and throttled callers. Each event is stored (collection orv_security) and
 * written to the log, so it can be reviewed in the console and shipped to a monitoring system.
 * Passwords and tokens are never part of an event.
 */
public final class SecurityLog {

    private static final Logger LOG = LoggerFactory.getLogger("orvanta.security");

    private final Platform platform;

    public SecurityLog(Platform platform) {
        this.platform = platform;
    }

    public void record(String type, String username, String address, String detail) {
        String user = clean(username, 64);
        String text = clean(detail, 300);
        try {
            platform.store.insert(DocStore.SECURITY, Rec.of("id", UUID.randomUUID().toString(), "type", type, "username", user,
                    "address", address, "detail", text, "at", Platform.now()));
        } catch (RuntimeException e) {
            // an event that cannot be stored must still reach the log
            LOG.error("security event could not be stored: {}", e.toString());
        }
        LOG.info("{} user={} address={} {}", type, user, address, text == null ? "" : text);
    }

    /** User-supplied text is cut and stripped of line breaks, so it cannot forge log lines. */
    private static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String oneLine = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        return oneLine.length() > max ? oneLine.substring(0, max) : oneLine;
    }
}
