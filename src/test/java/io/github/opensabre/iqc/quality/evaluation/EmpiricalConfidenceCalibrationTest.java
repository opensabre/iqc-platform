package io.github.opensabre.iqc.quality.evaluation;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmpiricalConfidenceCalibrationTest {
    private static EmpiricalConfidenceCalibration.Source source(String execution) {
        return new EmpiricalConfidenceCalibration.Source("task",execution,"a".repeat(64),"b".repeat(64),
                "c".repeat(64),"node","d".repeat(64),"QUALITY_HIT");
    }
    private static EmpiricalConfidenceCalibration.Artifact candidate() {
        long[] counts = new long[10], correct = new long[10];
        counts[0]=20; correct[0]=7;
        counts[1]=19; correct[1]=19;
        counts[9]=21; correct[9]=13;
        return EmpiricalConfidenceCalibration.fit(source("execution"),65,60,counts,correct);
    }
    @Test void exactCountsThresholdsAndScoreBoundaries() {
        var artifact=candidate();
        assertEquals(new BigDecimal("0.350000"),artifact.apply(BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.350000"),artifact.apply(new BigDecimal("0.099999")));
        assertNull(artifact.apply(new BigDecimal("0.1")));
        assertNull(artifact.apply(new BigDecimal("0.2")));
        assertNull(artifact.apply(null));
        assertEquals(new BigDecimal("0.619048"),artifact.apply(new BigDecimal("0.9")));
        assertEquals(new BigDecimal("0.619048"),artifact.apply(BigDecimal.ONE));
        assertEquals(13,artifact.bins().get(9).correct());
        assertThrows(IllegalArgumentException.class,()->artifact.apply(new BigDecimal("-0.000001")));
        assertThrows(IllegalArgumentException.class,()->artifact.apply(new BigDecimal("1.000001")));
        assertThrows(UnsupportedOperationException.class,()->artifact.bins().clear());
    }
    @Test void rejectsForgedProbabilityCountsAndEvidenceBinding() {
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Bin(0,20,7,new BigDecimal("0.36")));
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Bin(0,19,19,BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Bin(0,20,21,BigDecimal.ONE));
        var a=candidate();
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Artifact(a.schemaVersion(),a.method(),a.event(),source("another"),a.eligibleKnownGold(),a.scored(),a.minimumBinSamples(),a.bins(),a.contentHash()));
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Artifact(a.schemaVersion(),a.method(),a.event(),a.source(),a.eligibleKnownGold(),59,a.minimumBinSamples(),a.bins(),a.contentHash()));
        assertThrows(IllegalArgumentException.class,()->new EmpiricalConfidenceCalibration.Artifact(a.schemaVersion(),a.method(),a.event(),a.source(),a.eligibleKnownGold(),a.scored(),a.minimumBinSamples(),a.bins(),"0".repeat(64)));
        assertEquals(a.contentHash(),candidate().contentHash());
    }
}
