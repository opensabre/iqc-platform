package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.DecimalNode;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StructuredToolInputHashTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void exactInputDistinguishesDecimalsThatLegacyFloatingPointCollapses() throws Exception {
        var config=json.createObjectNode().put("inputHashAlgorithm",StructuredToolInputHash.EXACT);
        var left=json.createObjectNode();left.set("amount",DecimalNode.valueOf(new BigDecimal("99.999999999999999999")));
        var right=json.createObjectNode();right.set("amount",DecimalNode.valueOf(new BigDecimal("100.000000000000000000")));
        var messages=json.createArrayNode();
        assertThat(StructuredToolInputHash.hash(json,config,left,messages)).isNotEqualTo(StructuredToolInputHash.hash(json,config,right,messages));
    }
    @Test void unmarkedHistoricalSnapshotRetainsOriginalAlgorithm() throws Exception {
        var input=json.createObjectNode().put("recordId","1");var messages=json.createArrayNode();
        assertThat(StructuredToolInputHash.hash(json,json.createObjectNode(),input,messages))
                .isEqualTo(PipelineAssetService.hash(json.writeValueAsString(List.of(input,messages))));
        assertThatThrownBy(()->StructuredToolInputHash.validate(json.createObjectNode().put("inputHashAlgorithm","UNKNOWN")))
                .hasMessageContaining("Unsupported");
    }
}
