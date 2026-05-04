package io.devportal.runtime.k8s.dto;

public record MonitoringLinks(
    String k9s,         // shell hint, e.g. "k9s -n default --selector app=hitorro-core"
    String grafana,     // optional URL constructed from monitoringBaseUrl
    String kubectlLogs  // shell hint: "kubectl logs -l app=<id> -n <ns> --tail=200 -f"
) {}
