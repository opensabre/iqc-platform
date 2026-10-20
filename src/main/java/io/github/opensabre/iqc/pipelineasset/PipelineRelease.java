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
                              io.github.opensabre.iqc.label.FindingLabelPolicy labelPolicy,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              RiskPolicy riskPolicy,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              ReviewPolicy reviewPolicy) {
    public static final String SCHEMA = "iqc-pipeline-release-v1";
    public PipelineRelease(String schemaVersion,PipelineDefinition pipeline,FusionPolicy fusion,InspectionScoring.Policy scoring,
            List<ItemMapping> items,PublicationGatePolicy publicationGate,io.github.opensabre.iqc.action.ActionPolicy actionPolicy,
            io.github.opensabre.iqc.label.FindingLabelPolicy labelPolicy,RiskPolicy riskPolicy) {
        this(schemaVersion,pipeline,fusion,scoring,items,publicationGate,actionPolicy,labelPolicy,riskPolicy,null);
    }
    public PipelineRelease(String schemaVersion,PipelineDefinition pipeline,FusionPolicy fusion,InspectionScoring.Policy scoring,
            List<ItemMapping> items,PublicationGatePolicy publicationGate,io.github.opensabre.iqc.action.ActionPolicy actionPolicy,
            io.github.opensabre.iqc.label.FindingLabelPolicy labelPolicy) {
        this(schemaVersion,pipeline,fusion,scoring,items,publicationGate,actionPolicy,labelPolicy,null);
    }
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
            String appliesWhenFindingCode,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            NumericPredicate numericPredicate,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            NumericPredicate appliesWhenNumericPredicate) {
        public ItemMapping(String itemCode,String findingCode,SchemeDefinition.HitMeaning hitMeaning) {
            this(itemCode,findingCode,hitMeaning,null,null,null);
        }
        public ItemMapping(String itemCode,String findingCode,SchemeDefinition.HitMeaning hitMeaning,String appliesWhenFindingCode) {
            this(itemCode,findingCode,hitMeaning,appliesWhenFindingCode,null,null);
        }
        public ItemMapping {
            if(appliesWhenFindingCode!=null && appliesWhenFindingCode.isBlank()
                    || appliesWhenNumericPredicate!=null && appliesWhenFindingCode==null)
                throw new IllegalArgumentException("Numeric applicability requires a declared finding");
        }
    }
    /** Exact threshold data reuses the pipeline comparator; absent fields retain legacy boolean mapping. */
    public record NumericPredicate(PipelineDefinition.Operator operator,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=DecimalThresholdDeserializer.class) String threshold) {
        public NumericPredicate {
            if(operator==null || operator==PipelineDefinition.Operator.EXISTS || threshold==null || threshold.length()>64
                    || !threshold.matches("[-+]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
                throw new IllegalArgumentException("Numeric predicate requires a comparison and exact decimal string");
            var decimal=new java.math.BigDecimal(threshold);
            if(decimal.precision()>38 || decimal.scale()>18)throw new IllegalArgumentException("Numeric threshold exceeds precision/scale bounds");
        }
        public boolean matches(com.fasterxml.jackson.databind.JsonNode actual) {
            if(actual==null || !actual.isNumber())throw new IllegalArgumentException("Numeric predicate requires a numeric fact");
            return io.github.opensabre.iqc.pipeline.PipelineRuntime.matches(new PipelineDefinition.Condition("",operator,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(new java.math.BigDecimal(threshold))),actual);
        }
    }
    /** Local wire guard: Jackson's default String coercion must not normalize an already rounded JSON number. */
    public static final class DecimalThresholdDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<String> {
        @Override public String deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING))
                throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"Numeric threshold must be a decimal JSON string");
            return parser.getText();
        }
    }
    public void validateFindingTypes(java.util.Map<String,io.github.opensabre.iqc.finding.Finding.ValueType> types) {
        for(var item:items) {
            var expected=item.numericPredicate()==null?io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN:io.github.opensabre.iqc.finding.Finding.ValueType.NUMBER;
            if(types.get(item.findingCode())!=expected)throw new IllegalArgumentException("Business predicate differs from frozen finding type");
            if(item.appliesWhenFindingCode()!=null) {
                var gate=item.appliesWhenNumericPredicate()==null?io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN:io.github.opensabre.iqc.finding.Finding.ValueType.NUMBER;
                if(types.get(item.appliesWhenFindingCode())!=gate)throw new IllegalArgumentException("Applicability predicate differs from frozen finding type");
            }
        }
    }
    public PipelineRelease {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported pipeline release schema");
        Objects.requireNonNull(pipeline); Objects.requireNonNull(fusion); Objects.requireNonNull(scoring);
        if(fusion.method()==FusionPolicy.Method.WEIGHTED&&!fusion.nodeWeights().keySet().equals(
                pipeline.nodes().stream().map(PipelineDefinition.Node::nodeId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("weighted fusion must declare exactly the pipeline source node weights");
        if(reviewPolicy!=null)ReviewPolicy.validateRouting(actionPolicy);
        else if(actionPolicy!=null&&actionPolicy.actions().stream().anyMatch(action->ReviewPolicy.readsReview(action.condition())))
            throw new IllegalArgumentException("Review action condition requires a review policy");
        items = List.copyOf(items);
        var codes = new HashSet<String>();
        for (var item : items) if (item.itemCode() == null || item.itemCode().isBlank()
                || item.findingCode() == null || item.findingCode().isBlank() || item.hitMeaning() == null || !codes.add(item.itemCode()))
            throw new IllegalArgumentException("invalid or duplicate business mapping");
        var scored = new HashSet<String>(); scoring.items().forEach(item -> scored.add(item.itemCode()));
        if (!codes.equals(scored)) throw new IllegalArgumentException("scoring and finding consumers must match exactly");
    }
}
