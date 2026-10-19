package com.selfheal.common.model;

/**
 * Represents an injected link fault configuration.
 * INVARIANT: Link faults reside in the Healer's centralized Chaos Registry.
 */
public class LinkFault {
    private String caller;
    private String target;
    private int delayMs;
    private double lossPct;

    public LinkFault() {
    }

    public LinkFault(String caller, String target, int delayMs, double lossPct) {
        this.caller = caller;
        this.target = target;
        this.delayMs = delayMs;
        this.lossPct = lossPct;
    }

    public String getCaller() {
        return caller;
    }

    public void setCaller(String caller) {
        this.caller = caller;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public double getLossPct() {
        return lossPct;
    }

    public void setLossPct(double lossPct) {
        this.lossPct = lossPct;
    }

    @Override
    public String toString() {
        return "LinkFault{" +
               "caller='" + caller + '\'' +
               ", target='" + target + '\'' +
               ", delayMs=" + delayMs +
               ", lossPct=" + lossPct +
               '}';
    }
}
