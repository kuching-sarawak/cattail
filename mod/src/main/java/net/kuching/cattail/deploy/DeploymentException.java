package net.kuching.cattail.deploy;

import java.io.IOException;

/**
 * A deployment file failed validation. The message is {@code <key> <rule>}, for example
 * {@code tag must match tag:[a-z0-9-]+}; it never contains a value, because the file holds the
 * auth key.
 */
public final class DeploymentException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * @param key  the offending key, as written in the file
     * @param rule what the value must satisfy, without the value
     */
    public DeploymentException(String key, String rule) {
        super(key + " " + rule);
        this.key = key;
    }

    /** The offending key. */
    public String key() {
        return key;
    }
}
