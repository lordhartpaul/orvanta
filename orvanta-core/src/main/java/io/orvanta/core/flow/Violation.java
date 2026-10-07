package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;

/** One failed rule of a RuleSet. */
public record Violation(String rule, String code, String message, String severity) {

    public boolean isError() {
        return !"warning".equalsIgnoreCase(severity);
    }

    public Rec toRec() {
        return Rec.of("rule", rule, "code", code, "message", message, "severity", severity);
    }
}
