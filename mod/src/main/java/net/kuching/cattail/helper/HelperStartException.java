package net.kuching.cattail.helper;

import java.io.IOException;
import java.util.OptionalLong;

/**
 * The helper did not reach READY. {@link #reason()} is a short machine-readable code:
 * {@code ready-timeout}, {@code ready-malformed}, {@code helper-error:<code>},
 * {@code helper-exited:<exit code>} or {@code install-failed}.
 *
 * <p>{@link HelperManager#failureReason()} reports these and reasons of its own. The deployment
 * reasons are defined here: {@link #NOT_CONFIGURED} (the jar has no deployment file) and
 * {@link #DEPLOYMENT_INVALID}{@code <key>} (the file was rejected), both before anything is
 * started, and {@link #KEY_EXPIRED}, which replaces {@code helper-error:auth} once the
 * deployment's {@code authkey.expires} has passed. Its start reasons are {@code helper-missing},
 * {@code launch-failed}, {@code helper-crashed}, {@code internal-error} and {@code ready-timeout}.
 */
public final class HelperStartException extends IOException {

    /** Failure reason: the jar has no deployment file (a generic jar). */
    public static final String NOT_CONFIGURED = "not-configured";
    /** Failure reason prefix: the deployment file was rejected; the rejected key follows it. */
    public static final String DEPLOYMENT_INVALID = "deployment-invalid:";
    /** Failure reason: the helper's login was refused after the deployment's {@code authkey.expires}. */
    public static final String KEY_EXPIRED = "key-expired";

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final transient OptionalLong pid;

    HelperStartException(String reason, OptionalLong pid) {
        super(reason);
        this.reason = reason;
        this.pid = pid;
    }

    public String reason() {
        return reason;
    }

    /** PID of the (now destroyed) helper process, if it was launched. */
    public OptionalLong pid() {
        return pid == null ? OptionalLong.empty() : pid;
    }
}
