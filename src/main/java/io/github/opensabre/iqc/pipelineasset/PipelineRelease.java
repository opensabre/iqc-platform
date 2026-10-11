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
                              InspectionScoring.Policy scoring, List<ItemMapping> items,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              PublicationGatePolicy publicationGate,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              io.github.opensabre.iqc.action.ActionPolicy actionPolicy,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              io.github.opensabre.iqc.label.FindingLabelPolicy labelPolicy) {
    public static final String SCHEMA = "iqc-pipeline-release-v1";
    public PipelineRelease(String schemaVersion,PipelineDefinition pipeline,FusionPolicy fusion,
                           InspectionScoring.Policy scoring,List<ItemMapping> items,PublicationGatePolicy publicationGate,
                           io.github.opensabre.iqc.action.ActionPolicy actionPolicy) {
        this(schemaVersion,pipeline,fusion,scoring,items,publicationGate,actionPolicy,null);
    }
    public PipelineRelease(String schemaVersion,PipelineDefinition pipeline,FusionPolicy fusion,
                           InspectionScoring.Policy scoring,List<ItemMapping> items,PublicationGatePolicy publicationGate) {
        this(schemaVersion,pipeline,fusion,scoring,items,publicationGate,null,null);
    }
    public PipelineRelease(String schemaVersion,PipelineDefinition pipeline,FusionPolicy fusion,
                           InspectionScoring.Policy scoring,List<ItemMapping> items) {
        this(schemaVersion,pipeline,fusion,scoring,items,null,null,null);
    }
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
