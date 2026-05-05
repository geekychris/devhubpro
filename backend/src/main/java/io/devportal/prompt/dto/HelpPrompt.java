package io.devportal.prompt.dto;

public record HelpPrompt(
    String assetId,
    String problem,
    String text   // ready-to-paste markdown for Claude Code
) {}
