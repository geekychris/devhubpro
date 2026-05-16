package io.devportal.build;

public enum BuildStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public String dbValue() { return name().toLowerCase(); }

    public static BuildStatus fromDb(String s) {
        return BuildStatus.valueOf(s.toUpperCase());
    }
}
