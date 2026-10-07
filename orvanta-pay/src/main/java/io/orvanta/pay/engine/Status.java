package io.orvanta.pay.engine;

import java.util.List;

/** Status vocabulary of the engine. */
public final class Status {

    private Status() {
    }

    // inbound messages (instructions and acknowledgements)
    public static final String RECEIVED = "RECEIVED";
    public static final String DEBULKING = "DEBULKING";
    public static final String DEBULKED = "DEBULKED";
    public static final String PROCESSED = "PROCESSED";
    public static final String REJECTED = "REJECTED";

    // transactions
    public static final String STAGED = "STAGED";
    public static final String CREATED = "CREATED";
    public static final String PROCESSING = "PROCESSING";
    public static final String ROUTED = "ROUTED";
    public static final String BULKED = "BULKED";
    public static final String SENT = "SENT";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED_BY_APPLICATION = "REJECTED_BY_APPLICATION";
    public static final String REJECTED_BY_EXTERNAL = "REJECTED_BY_EXTERNAL";
    public static final String REPAIR = "REPAIR";
    public static final String HELD = "HELD";
    public static final String WAITING = "WAITING";
    public static final String WAREHOUSED = "WAREHOUSED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RETURNED = "RETURNED";
    /** An incoming payment that was credited to the customer's account. */
    public static final String CREDITED = "CREDITED";
    /** An incoming direct debit collection that was debited from the customer's account. */
    public static final String DEBITED = "DEBITED";

    // outbound files
    public static final String DISPATCHING = "DISPATCHING";
    public static final String FAILED = "FAILED";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";

    public static final List<String> TRANSACTION = List.of(CREATED, PROCESSING, HELD, WAITING, WAREHOUSED, ROUTED, BULKED, SENT, ACCEPTED,
            CREDITED, DEBITED,
            REJECTED_BY_APPLICATION, REJECTED_BY_EXTERNAL, CANCELLED, RETURNED, REPAIR);
}
