package net.kuching.cattail.helper;

/** Lifecycle of the Cattail helper process as seen by {@link HelperManager}. */
public enum HelperStatus {
    /** No helper is running and none is being started. */
    STOPPED,
    /** A helper is being installed or launched and has not printed READY yet. */
    STARTING,
    /** The helper printed READY; its loopback port accepts game connections. */
    READY,
    /** Starting failed or the helper crashed twice; see {@link HelperManager#failureReason()}. */
    FAILED
}
