package app.ingest.api;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(@NotBlank String query) {}
