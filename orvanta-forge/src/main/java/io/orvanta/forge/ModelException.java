package io.orvanta.forge;

/** A defect in a model, with the place inside the model where it was found. */
public class ModelException extends RuntimeException {

    private final String where;

    public ModelException(String where, String message) {
        super(message);
        this.where = where;
    }

    public String where() {
        return where;
    }
}
