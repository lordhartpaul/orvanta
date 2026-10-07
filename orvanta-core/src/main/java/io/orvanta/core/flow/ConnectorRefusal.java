package io.orvanta.core.flow;

/**
 * An external system answered, and its answer was no: the request was understood and refused (HTTP 4xx).
 * Unlike an outage, asking again cannot help, so nothing is retried; a person looks at what the system said.
 */
public final class ConnectorRefusal extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private final int status;

    public ConnectorRefusal(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
