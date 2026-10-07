package io.github.opensabre.iqc.modelprofile;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.governance.IqcException;
import java.math.BigDecimal;
import java.util.*;

/** Declared flat token pricing. No market price, exchange rate or billing discount is inferred. */
public record ModelPricing(String schemaVersion,String currency,String inputPerMillion,String outputPerMillion) {
    public ModelPricing {
        if(!"iqc-model-flat-pricing-v1".equals(schemaVersion) || currency==null || !currency.matches("[A-Z]{3}"))throw IqcException.invalidArgument("模型定价协议或币种无效");
        try{Currency.getInstance(currency);}catch(IllegalArgumentException invalid){throw IqcException.invalidArgument("模型定价币种无效");}
        inputPerMillion=price(inputPerMillion);outputPerMillion=price(outputPerMillion);
    }
    private static String price(String value){
        if(value==null || !value.matches("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,12})?"))throw IqcException.invalidArgument("单价必须为非负精确十进制字符串");
        var amount=new BigDecimal(value);if(amount.compareTo(new BigDecimal("1000000"))>0)throw IqcException.invalidArgument("模型单价超限");
        return amount.stripTrailingZeros().toPlainString();
    }
    public static String canonical(String source){
        if(source==null || source.isBlank())return null;
        if(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw IqcException.invalidArgument("定价配置超限");
        try{
            var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var node=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(source);
            var allowed=Set.of("schemaVersion","currency","inputPerMillion","outputPerMillion");
            if(!node.isObject() || node.size()!=allowed.size())throw IqcException.invalidArgument("定价字段无效");
            var fields=node.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))throw IqcException.invalidArgument("未知定价字段");
            for(String field:allowed)if(!node.path(field).isTextual())throw IqcException.invalidArgument("定价字段必须为字符串");
            return mapper.writeValueAsString(new ModelPricing(node.path("schemaVersion").textValue(),node.path("currency").textValue(),
                    node.path("inputPerMillion").textValue(),node.path("outputPerMillion").textValue()));
        }catch(IqcException invalid){throw invalid;}catch(Exception invalid){throw IqcException.invalidArgument("定价JSON无效");}
    }
}
