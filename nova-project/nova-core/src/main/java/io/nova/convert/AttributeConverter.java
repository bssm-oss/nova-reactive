package io.nova.convert;

public interface AttributeConverter<X, Y> {
    Y write(X source);

    X read(Y source);
}
