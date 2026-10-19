package com.selfheal.common.model;

import java.util.Objects;

/**
 * Runtime policy for outgoing service-to-service links.
 * Changeable at runtime via Healer policy tuning or circuit breaking.
 */
public class EdgePolicy {
    private int timeoutMs;
    private int maxRetries;
    private int retryBudgetPct;
    private boolean breakerOpen;

    public EdgePolicy() {
        this.timeoutMs = 800;
        this.maxRetries = 2;
        this.retryBudgetPct = 20;
        this.breakerOpen = false;
    }

    public EdgePolicy(int timeoutMs, int maxRetries, int retryBudgetPct, boolean breakerOpen) {
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryBudgetPct = retryBudgetPct;
        this.breakerOpen = breakerOpen;
    }

    public EdgePolicy(EdgePolicy other) {
        this.timeoutMs = other.timeoutMs;
        this.maxRetries = other.maxRetries;
        this.retryBudgetPct = other.retryBudgetPct;
        this.breakerOpen = other.breakerOpen;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryBudgetPct() {
        return retryBudgetPct;
    }

    public void setRetryBudgetPct(int retryBudgetPct) {
        this.retryBudgetPct = retryBudgetPct;
    }

    public boolean isBreakerOpen() {
        return breakerOpen;
    }

    public void setBreakerOpen(boolean breakerOpen) {
        this.breakerOpen = breakerOpen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EdgePolicy that = (EdgePolicy) o;
        return timeoutMs == that.timeoutMs &&
               maxRetries == that.maxRetries &&
               retryBudgetPct == that.retryBudgetPct &&
               breakerOpen == that.breakerOpen;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeoutMs, maxRetries, retryBudgetPct, breakerOpen);
    }

    @Override
    public String toString() {
        return "EdgePolicy{" +
               "timeoutMs=" + timeoutMs +
               ", maxRetries=" + maxRetries +
               ", retryBudgetPct=" + retryBudgetPct +
               ", breakerOpen=" + breakerOpen +
               '}';
    }
}
