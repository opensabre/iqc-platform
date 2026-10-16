package io.github.opensabre.iqc.privacy;

import io.github.opensabre.boot.sensitive.rule.DefaultSensitiveRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;

/** Read-only text projection using the installed framework catalog; never changes canonical content or evidence. */
public final class SensitiveTextProjection {
    public static final String POLICY="iqc-framework-text-mask-v1";
    private static final List<DefaultSensitiveRule> RULES=List.of(DefaultSensitiveRule.PASSWORD,DefaultSensitiveRule.ID_CARD,
            DefaultSensitiveRule.BANK_CARD,DefaultSensitiveRule.MOBILE,DefaultSensitiveRule.PHONE,
            DefaultSensitiveRule.EMAIL,DefaultSensitiveRule.NAME,DefaultSensitiveRule.CAR_LICENSE,DefaultSensitiveRule.ADDRESS);
    private SensitiveTextProjection() { }
    /** Length refers to Java/JavaScript UTF-16 positions; source offsets are not recomputed from masked content. */
    public record Projection(String policyVersion,String text,boolean redacted,List<String> categories){
        public Projection {categories=List.copyOf(categories);}
    }
    public static Projection mask(String original){
        if(original==null||original.isEmpty())return new Projection(POLICY,original,false,List.of());
        String projected=original;var categories=new ArrayList<String>();
        for(var rule:RULES){
            var matcher=rule.pattern().matcher(projected);var buffer=new StringBuilder();boolean changed=false;
            while(matcher.find()){
                String matched=matcher.group();if(matched.isEmpty())continue;
                String replacement;
                try{replacement=rule.replace(matched);}catch(RuntimeException invalidRuleRange){replacement=null;}
                // An unusable range must not leave a matched value intact or shift evidence positions.
                if(replacement==null||replacement.length()!=matched.length()||replacement.equals(matched))replacement="*".repeat(matched.length());
                matcher.appendReplacement(buffer,Matcher.quoteReplacement(replacement));changed=true;
            }
            matcher.appendTail(buffer);
            if(changed){projected=buffer.toString();categories.add(rule.category());}
        }
        return new Projection(POLICY,projected,!Objects.equals(original,projected),categories);
    }
}
