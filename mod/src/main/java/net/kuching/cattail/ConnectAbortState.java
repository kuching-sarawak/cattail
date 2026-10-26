package net.kuching.cattail;

/**
 * Implemented on {@code ConnectScreen} by {@code ConnectScreenMixin}: lets the connector thread see
 * whether the player pressed Cancel. Lives outside the mixin package so mixins can cast to it.
 */
public interface ConnectAbortState {
    boolean cattail$isAborted();
}
