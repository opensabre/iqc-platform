package io.github.opensabre.iqc.fusion;

import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import java.util.Map;

/** Immutable v1 implementation. Publish a new class/version instead of modifying released behavior. */
public final class UnanimousFusionExtension implements FusionExtension {
    @Override public Definition definition(){return new Definition("UNANIMOUS",1,"全部来源一致同意",Map.of("minimumSources","1"));}
    @Override public void validateParameters(Map<String,String> parameters){
        if(!parameters.keySet().stream().allMatch("minimumSources"::equals))throw new IllegalArgumentException("Unknown unanimity parameter");
        minimum(parameters);
    }
    private int minimum(Map<String,String> parameters){
        var value=parameters.getOrDefault("minimumSources","1");
        if(!value.matches("[1-9][0-9]{0,2}")||Integer.parseInt(value)>200)
            throw new IllegalArgumentException("minimumSources must be an integer string within 1..200");
        return Integer.parseInt(value);
    }
    @Override public Outcome evaluate(List<Finding> raw,Map<String,String> parameters){
        validateParameters(parameters);
        if(raw.stream().map(finding->finding.source().nodeId()).distinct().count()!=raw.size())
            throw new IllegalArgumentException("Unanimity requires one observation per source node");
        var state=FindingFusion.resolve(raw);
        if(state==Finding.State.KNOWN&&raw.size()<minimum(parameters))state=Finding.State.UNKNOWN;
        return new Outcome(raw.stream().map(Finding::id).toList(),state,state==Finding.State.KNOWN?raw.getFirst().value():null,
                "All observed sources must agree and be known; minimumSources="+minimum(parameters)+", observedSources="+raw.size());
    }
}
