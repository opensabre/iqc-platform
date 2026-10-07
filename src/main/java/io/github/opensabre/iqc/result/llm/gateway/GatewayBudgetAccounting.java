package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.iqc.modelprofile.ModelPricing;
import java.math.BigDecimal;
import java.util.Objects;

/** Called only while the owning capacity row is locked; never performs remote I/O. */
final class GatewayBudgetAccounting {
    private GatewayBudgetAccounting() { }
    static void reserve(GatewayCapacity bucket,GatewayCall call){
        if(Boolean.TRUE.equals(bucket.getBudgetBreached()))throw new GatewayAdmissionException("MODEL_BUDGET_QUARANTINED");
        if(bucket.getTokenBudgetLimit()==null && bucket.getCostBudgetLimit()==null){call.setBudgetStatus("NOT_CONFIGURED");return;}
        if(bucket.getPolicyVersion()==null || bucket.getPolicyVersion()<0 || bucket.getBudgetContextWindow()==null || bucket.getBudgetContextWindow()<1 || bucket.getBudgetContextWindow()>100000000
                || bucket.getBudgetEvidenceRef()==null || !bucket.getBudgetEvidenceRef().matches("[A-Za-z0-9:_./-]{1,128}")
                || bucket.getBudgetDeploymentRef()==null || !bucket.getBudgetDeploymentRef().matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}")
                || !Objects.equals(bucket.getBudgetContextWindow(),call.getContextWindowTokens())
                || !Objects.equals(bucket.getBudgetDeploymentRef(),call.getDeploymentRef())
                || !Objects.equals(bucket.getBudgetOutputParameter(),call.getOutputTokenParameter()) || bucket.getBudgetOutputParameter()==null
                || call.getMaxOutputTokens()==null || call.getMaxOutputTokens()<1 || call.getMaxOutputTokens()>bucket.getBudgetContextWindow()
                || !Boolean.TRUE.equals(call.getOutputLimitSingleResponse()))throw new GatewayAdmissionException("MODEL_BUDGET_BOUND_UNVERIFIED");
        long bound=bucket.getBudgetContextWindow();
        long committed=add(tokens(bucket.getTokenBudgetCommitted()),bound);
        if(bucket.getTokenBudgetLimit()!=null && (bucket.getTokenBudgetLimit()<0 || committed>bucket.getTokenBudgetLimit()))
            throw new GatewayAdmissionException("MODEL_TOKEN_BUDGET_EXHAUSTED");
        BigDecimal reservedCost=null;
        if(bucket.getCostBudgetLimit()!=null){
            var price=ModelPricing.parse(call.getPricingJson());
            if(price==null || bucket.getBudgetPricingHash()==null || !bucket.getBudgetPricingHash().matches("[a-f0-9]{64}")
                    || !Objects.equals(bucket.getBudgetPricingHash(),call.getPricingHash()) || !Boolean.TRUE.equals(call.getBillingSupported()) || !Objects.equals(price.currency(),bucket.getBudgetCurrency()))
                throw new GatewayAdmissionException("MODEL_COST_BUDGET_PRICE_UNVERIFIED");
            var input=new BigDecimal(price.inputPerMillion());var output=new BigDecimal(price.outputPerMillion());
            reservedCost=BigDecimal.valueOf(bound).multiply(input)
                    .add(BigDecimal.valueOf(call.getMaxOutputTokens()).multiply(output.subtract(input).max(BigDecimal.ZERO))).movePointLeft(6);
            var cost=amount(bucket.getCostBudgetCommitted()).add(reservedCost);
            if(amount(bucket.getCostBudgetLimit()).compareTo(cost)<0)throw new GatewayAdmissionException("MODEL_COST_BUDGET_EXHAUSTED");
            bucket.setCostBudgetCommitted(storable(cost));call.setBudgetCurrency(price.currency());
        }
        bucket.setTokenBudgetCommitted(committed);call.setBudgetReservedTokens(bound);call.setBudgetReservedCost(reservedCost);
        call.setBudgetEvidenceRef(bucket.getBudgetEvidenceRef());call.setBudgetPolicyVersion(bucket.getPolicyVersion());call.setBudgetStatus("HELD");
    }
    static boolean settle(GatewayCapacity bucket,GatewayCall call,GatewayCallService.Usage usage){
        if(call.getBudgetReservedTokens()==null)return false;
        boolean changed=false,tokenKnown="COMPLETE".equals(usage.status());
        boolean breached=!"INVALID".equals(usage.status()) && ((usage.totalTokens()!=null && usage.totalTokens()>call.getBudgetReservedTokens())
                || (usage.outputTokens()!=null && call.getMaxOutputTokens()!=null && usage.outputTokens()>call.getMaxOutputTokens()));
        if(tokenKnown){
            long committed=tokens(bucket.getTokenBudgetCommitted()),held=call.getBudgetReservedTokens();
            if(committed<held)throw new GatewayAdmissionException("MODEL_BUDGET_EVIDENCE_INVALID");
            long next=add(committed-held,usage.totalTokens());
            changed=next!=committed;bucket.setTokenBudgetCommitted(next);

        }
        boolean costKnown=call.getBudgetReservedCost()==null || "CALCULATED_FROM_DECLARED_PRICE".equals(call.getCostStatus());
        if(call.getBudgetReservedCost()!=null && costKnown){
            if(!Objects.equals(bucket.getBudgetCurrency(),call.getBudgetCurrency()))throw new GatewayAdmissionException("MODEL_BUDGET_CURRENCY_CHANGED");
            var committed=amount(bucket.getCostBudgetCommitted());var held=amount(call.getBudgetReservedCost());var actual=amount(call.getCalculatedCost());
            if(committed.compareTo(held)<0)throw new GatewayAdmissionException("MODEL_BUDGET_EVIDENCE_INVALID");
            var next=storable(committed.subtract(held).add(actual));changed|=next.compareTo(committed)!=0;bucket.setCostBudgetCommitted(next);
            breached|=actual.compareTo(held)>0;
        }
        if(call.getBudgetReservedCost()!=null && ("UNKNOWN_CACHE_BILLING".equals(call.getCostStatus()) || "PRICING_EVIDENCE_INVALID".equals(call.getCostStatus())))
            breached=true;
        if(breached){changed|=!Boolean.TRUE.equals(bucket.getBudgetBreached());bucket.setBudgetBreached(true);call.setBudgetStatus("QUARANTINED");}
        else call.setBudgetStatus(tokenKnown?(costKnown?"SETTLED":"TOKEN_SETTLED_COST_HELD"):"HELD");
        return changed;
    }
    private static long tokens(Long value){if(value==null || value<0)throw new GatewayAdmissionException("MODEL_BUDGET_EVIDENCE_INVALID");return value;}
    private static long add(long left,long right){try{return Math.addExact(left,right);}catch(ArithmeticException overflow){throw new GatewayAdmissionException("MODEL_BUDGET_ACCOUNTING_OVERFLOW");}}
    private static BigDecimal amount(BigDecimal value){if(value==null || value.signum()<0)throw new GatewayAdmissionException("MODEL_BUDGET_EVIDENCE_INVALID");return storable(value);}
    private static BigDecimal storable(BigDecimal value){
        var plain=value.stripTrailingZeros();
        if(Math.max(0,plain.scale())>18 || plain.precision()-plain.scale()>20)throw new GatewayAdmissionException("MODEL_BUDGET_ACCOUNTING_OVERFLOW");
        return value;
    }
}
