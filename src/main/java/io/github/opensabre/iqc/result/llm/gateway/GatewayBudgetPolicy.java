package io.github.opensabre.iqc.result.llm.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;
import java.math.BigDecimal;
import java.util.*;

/** Explicit whole-policy replacement, applied under the existing capacity version fence. */
final class GatewayBudgetPolicy {
    private GatewayBudgetPolicy() { }
    static void apply(GatewayCapacity row,JsonNode command){
        if(command==null || command.isNull())return;
        var fields=Set.of("tokenLimit","costLimit","currency","contextWindow","deploymentRef","outputParameter","pricingHash","evidenceRef","contractVerified");
        if(!command.isObject() || command.size()!=fields.size())throw invalid();
        command.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw invalid();});
        Long tokens=null;var token=text(command,"tokenLimit",true);
        if(token!=null){if(!token.matches("0|[1-9][0-9]{0,18}"))throw invalid();try{tokens=Long.parseLong(token);}catch(NumberFormatException bad){throw invalid();}}
        BigDecimal cost=null;var amount=text(command,"costLimit",true);
        if(amount!=null){if(!amount.matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,18})?"))throw invalid();cost=new BigDecimal(amount);}
        if(tokens==null && cost==null)throw IqcException.invalidArgument("预算需至少设置Token或金额限额，不能通过清空停止累计计量");
        var window=command.get("contextWindow");
        if(window==null || !window.isIntegralNumber() || !window.canConvertToInt() || window.intValue()<1 || window.intValue()>100000000)throw invalid();
        var deployment=text(command,"deploymentRef",false);var parameter=text(command,"outputParameter",false);var evidence=text(command,"evidenceRef",false);
        if(!deployment.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}") || !Set.of("MAX_TOKENS","MAX_COMPLETION_TOKENS").contains(parameter)
                || !evidence.matches("[A-Za-z0-9:_./-]{1,128}") || !command.path("contractVerified").isBoolean() || !command.path("contractVerified").booleanValue())throw invalid();
        var currency=text(command,"currency",true);var hash=text(command,"pricingHash",true);
        if(cost!=null){
            if(currency==null || !currency.matches("[A-Z]{3}") || hash==null || !hash.matches("[a-f0-9]{64}"))throw invalid();
            try{if(Currency.getInstance(currency).getDefaultFractionDigits()<0)throw invalid();}catch(IllegalArgumentException bad){throw invalid();}
        }else if(hash!=null || (currency!=null && !Objects.equals(currency,row.getBudgetCurrency())))throw invalid();
        if(row.getBudgetCurrency()!=null && currency!=null && !Objects.equals(row.getBudgetCurrency(),currency))
            throw IqcException.invalidState("预算账本币种不可替换");
        // A limit may be lowered below current commitments; existing accounting is never reset.
        row.setTokenBudgetLimit(tokens);row.setCostBudgetLimit(cost);
        if(currency!=null)row.setBudgetCurrency(currency);
        row.setBudgetContextWindow(window.intValue());row.setBudgetDeploymentRef(deployment);row.setBudgetOutputParameter(parameter);
        row.setBudgetPricingHash(hash);row.setBudgetEvidenceRef(evidence);
    }
    private static String text(JsonNode node,String field,boolean nullable){
        var value=node.get(field);if(value==null)throw invalid();
        if(value.isNull() && nullable)return null;if(!value.isTextual())throw invalid();return value.textValue();
    }
    private static RuntimeException invalid(){return IqcException.invalidArgument("预算配置格式或核定依据无效");}
}
