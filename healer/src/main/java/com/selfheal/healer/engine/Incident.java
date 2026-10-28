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
        NO_HEAL,
        BASELINE,
        PROPOSED_NO_LINK,
        PROPOSED_NO_ATTRIB,
        PROPOSED
    }

    public static class IncidentActionRecord {
        private final String id;
        private final int stepNum;
        private final String actionType;
        private final String targetComponent;
        private final long startedAt;
        private final String faultTypeAssumed;
        private final Double qBefore;
        private Long completedAt;
        private Double postActionZ;
        private Double qAfter;
        private String suspectAfter;
        private String verdict; // SUCCESS, WRONG_TARGET, INEFFECTIVE_ACTION, INCONCLUSIVE

        public IncidentActionRecord(String id, int stepNum, String actionType, String targetComponent,
                                    long startedAt, String faultTypeAssumed, Double qBefore) {
            if (!isValidTarget(targetComponent)) {
                throw new IllegalArgumentException("Action target must be a non-empty component name.");
            }
            this.id = id;
            this.stepNum = stepNum;
            this.actionType = actionType;
            this.targetComponent = targetComponent;
            this.startedAt = startedAt;
            this.faultTypeAssumed = faultTypeAssumed;
            this.qBefore = qBefore;
        }

        public String getId() { return id; }
        public int getStepNum() { return stepNum; }
        public String getActionType() { return actionType; }
        public String getTargetComponent() { return targetComponent; }
        public long getStartedAt() { return startedAt; }
        public String getFaultTypeAssumed() { return faultTypeAssumed; }
        public Double getQBefore() { return qBefore; }
        public Long getCompletedAt() { return completedAt; }
        public Double getPostActionZ() { return postActionZ; }
        public Double getQAfter() { return qAfter; }
        public String getSuspectAfter() { return suspectAfter; }
        public String getVerdict() { return verdict; }

        public void complete(long completedAt, Double postActionZ, String verdict) {
            this.completedAt = completedAt;
            this.postActionZ = postActionZ;
            this.verdict = verdict;
        }

        public void setQAfter(Double qAfter) {
            this.qAfter = qAfter;
        }

        public void setSuspectAfter(String suspectAfter) {
            this.suspectAfter = suspectAfter;
        }
    }

    private final String id;
    private final long startedAt;
    private Long resolvedAt;
    private final String triggerReason;
    private final OperatingMode mode;
    private String rootCauseType;
    private String targetEntity;
    private String diagnosisSource;
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
    public synchronized String getDiagnosisSource() { return diagnosisSource; }
    public synchronized Status getStatus() { return status; }
    public synchronized int getVerificationWindowsLeft() { return verificationWindowsLeft; }
    public synchronized List<IncidentActionRecord> getActions() { return Collections.unmodifiableList(actions); }

    public synchronized void setDiagnosis(String targetEntity, String rootCauseType) {
        setDiagnosis(targetEntity, rootCauseType, "NA");
    }

    public synchronized void setDiagnosis(String targetEntity, String rootCauseType, String diagnosisSource) {
        if (!isValidTarget(targetEntity)) {
            throw new IllegalArgumentException("Diagnosis candidate must be a non-empty component name.");
        }
        this.targetEntity = targetEntity;
        this.rootCauseType = rootCauseType;
        this.diagnosisSource = diagnosisSource;
    }

    public static boolean isValidTarget(String target) {
        if (target == null) {
            return false;
        }
        String normalized = target.trim();
        return !normalized.isEmpty()
                && !"null".equalsIgnoreCase(normalized)
                && !"na".equalsIgnoreCase(normalized);
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
