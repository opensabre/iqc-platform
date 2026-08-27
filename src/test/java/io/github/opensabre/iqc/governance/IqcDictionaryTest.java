package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IqcDictionaryTest {

    @Test
    void taskStatusIsFrameworkDictionary() {
        assertEquals("iqc_task_status", IqcTaskStatus.class.getAnnotation(OpenSabreDictionary.class).code());
        assertTrue(DictionaryEnum.class.isAssignableFrom(IqcTaskStatus.class));
    }

    @Test
    void ruleTypeAndRiskLevelAreFrameworkDictionaries() {
        assertEquals("iqc_rule_type", IqcRuleType.class.getAnnotation(OpenSabreDictionary.class).code());
        assertEquals("iqc_risk_level", IqcRiskLevel.class.getAnnotation(OpenSabreDictionary.class).code());
    }

    @Test
    void resultStatusAndTargetRoleAreFrameworkDictionaries() {
        assertEquals("iqc_result_status", IqcResultStatus.class.getAnnotation(OpenSabreDictionary.class).code());
        assertEquals("iqc_target_role", IqcTargetRole.class.getAnnotation(OpenSabreDictionary.class).code());
        assertTrue(DictionaryEnum.class.isAssignableFrom(IqcResultStatus.class));
        assertTrue(DictionaryEnum.class.isAssignableFrom(IqcTargetRole.class));
    }

    @Test
    void agentModelAndMcpOptionsAreFrameworkDictionaries() {
        assertEquals("iqc_model_provider", IqcModelProvider.class.getAnnotation(OpenSabreDictionary.class).code());
        assertEquals("iqc_mcp_transport", IqcMcpTransport.class.getAnnotation(OpenSabreDictionary.class).code());
        assertEquals("iqc_mcp_auth_type", IqcMcpAuthType.class.getAnnotation(OpenSabreDictionary.class).code());
        assertEquals("iqc_agent_asset_status", IqcAssetStatus.class.getAnnotation(OpenSabreDictionary.class).code());
    }
}
