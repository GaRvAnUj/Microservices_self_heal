package com.selfheal.healer.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Incident {

    public enum Status {
        OPEN,
        DIAGNOSING,
        ACTION_EXECUTED,
        VERIFYING,
        RESOLVED,
        ESCALATED
    }

    public enum OperatingMode {
        BASELINE,
        PROPOSED
    }

    public static class IncidentActionRecord {
        private final String id;
        private final int stepNum;
        private final String actionType;
        private final String targetComponent;
        private final long startedAt;
        private Long completedAt;
        private Double postActionZ;
        private String verdict; // SUCCESS, WRONG_TARGET, INEFFECTIVE_ACTION, INCONCLUSIVE

        public IncidentActionRecord(String id, int stepNum, String actionType, String targetComponent, long startedAt) {
            this.id = id;
            this.stepNum = stepNum;
            this.actionType = actionType;
            this.targetComponent = targetComponent;
            this.startedAt = startedAt;
        }

        public String getId() { return id; }
        public int getStepNum() { return stepNum; }
        public String getActionType() { return actionType; }
        public String getTargetComponent() { return targetComponent; }
        public long getStartedAt() { return startedAt; }
        public Long getCompletedAt() { return completedAt; }
        public Double getPostActionZ() { return postActionZ; }
        public String getVerdict() { return verdict; }

        public void complete(long completedAt, Double postActionZ, String verdict) {
            this.completedAt = completedAt;
            this.postActionZ = postActionZ;
            this.verdict = verdict;
        }
    }

    private final String id;
    private final long startedAt;
    private Long resolvedAt;
    private final String triggerReason;
    private final OperatingMode mode;
    private String rootCauseType;
    private String targetEntity;
    private Status status;
    private int verificationWindowsLeft;
    private final List<IncidentActionRecord> actions = new ArrayList<>();

    public Incident(String id, long startedAt, String triggerReason, OperatingMode mode) {
        this.id = id;
        this.startedAt = startedAt;
        this.triggerReason = triggerReason;
        this.mode = mode;
        this.status = Status.OPEN;
        this.verificationWindowsLeft = 0;
    }

    public String getId() { return id; }
    public long getStartedAt() { return startedAt; }
    public Long getResolvedAt() { return resolvedAt; }
    public String getTriggerReason() { return triggerReason; }
    public OperatingMode getMode() { return mode; }
    public synchronized String getRootCauseType() { return rootCauseType; }
    public synchronized String getTargetEntity() { return targetEntity; }
    public synchronized Status getStatus() { return status; }
    public synchronized int getVerificationWindowsLeft() { return verificationWindowsLeft; }
    public synchronized List<IncidentActionRecord> getActions() { return Collections.unmodifiableList(actions); }

    public synchronized void setDiagnosis(String targetEntity, String rootCauseType) {
        this.targetEntity = targetEntity;
        this.rootCauseType = rootCauseType;
    }

    public synchronized void setStatus(Status status) {
        this.status = status;
    }

    public synchronized void startVerification(int windows) {
        this.status = Status.VERIFYING;
        this.verificationWindowsLeft = windows;
    }

    public synchronized int decrementVerificationWindow() {
        return --this.verificationWindowsLeft;
    }

    public synchronized void addAction(IncidentActionRecord action) {
        this.actions.add(action);
    }

    public synchronized IncidentActionRecord getLatestAction() {
        return actions.isEmpty() ? null : actions.get(actions.size() - 1);
    }

    public synchronized void resolve(long resolvedAt) {
        this.status = Status.RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    public synchronized void escalate() {
        this.status = Status.ESCALATED;
    }
}
