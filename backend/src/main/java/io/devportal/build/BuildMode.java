package io.devportal.build;

public enum BuildMode {
    SHALLOW, DEEP;

    public String dbValue() { return name().toLowerCase(); }

    public static BuildMode fromDb(String s) {
        return BuildMode.valueOf(s.toUpperCase());
    }
}
