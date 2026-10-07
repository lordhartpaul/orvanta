package io.orvanta.forge;

import io.orvanta.core.data.Rec;

/** One build problem: which model, where in it, and what is wrong. */
public record Problem(String element, String where, String message) {

    public Rec toRec() {
        return Rec.of("element", element, "where", where, "message", message);
    }

    @Override
    public String toString() {
        return element + (where == null || where.isEmpty() ? "" : " [" + where + "]") + ": " + message;
    }
}
