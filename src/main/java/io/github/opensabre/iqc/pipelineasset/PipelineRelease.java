package io.github.opensabre.iqc.pipelineasset;

import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;

/** Pipeline and deterministic policies are frozen together; detector assets are embedded in node config. */
public record PipelineRelease(String schemaVersion, PipelineDefinition pipeline, FusionPolicy fusion,
                              InspectionScoring.Policy scoring, List<ItemMapping> items) {
    public static final String SCHEMA = "iqc-pipeline-release-v1";
    public record ItemMapping(String itemCode, String findingCode, SchemeDefinition.HitMeaning hitMeaning,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String appliesWhenFindingCode) {
        public ItemMapping(String itemCode,String findingCode,SchemeDefinition.HitMeaning hitMeaning) {
            this(itemCode,findingCode,hitMeaning,null);
        }
    }
    public PipelineRelease {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported pipeline release schema");
        Objects.requireNonNull(pipeline); Objects.requireNonNull(fusion); Objects.requireNonNull(scoring);
        items = List.copyOf(items);
        var codes = new HashSet<String>();
        for (var item : items) if (item.itemCode() == null || item.itemCode().isBlank()
                || item.findingCode() == null || item.findingCode().isBlank() || item.hitMeaning() == null || !codes.add(item.itemCode()))
            throw new IllegalArgumentException("invalid or duplicate business mapping");
        var scored = new HashSet<String>(); scoring.items().forEach(item -> scored.add(item.itemCode()));
        if (!codes.equals(scored)) throw new IllegalArgumentException("scoring and finding consumers must match exactly");
    }
}
