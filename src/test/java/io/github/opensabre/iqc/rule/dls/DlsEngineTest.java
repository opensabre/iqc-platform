package io.github.opensabre.iqc.rule.dls;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DlsEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void expandsSlotsAndAppliesNegativeRuleInsideMessageWindow() throws Exception {
        DlsRuleDocument document = new DlsRuleDocument("1.0", new DlsRuleDocument.Source("rules.xlsx", "辱骂"), List.of(
                definition("slot_bad", "SLOT", "笨蛋;混蛋", "all"),
                definition("slot_quote", "SLOT", "你说", "all"),
                definition("rule_abuse", "RULE", "[slot_bad]", "agent"),
                definition("rule_quote", "RULE", "[slot_quote].{0,3}[slot_bad]", "agent")
        ), "辱骂客户", "([rule_abuse]&![rule_quote])<1>");

        DlsEngine.Compiled compiled = DlsEngine.compile(objectMapper, objectMapper.writeValueAsString(document));
        DlsEngine.Evaluation hit = DlsEngine.evaluate(compiled, List.of(message("m1", 1, "agent", "你真是个笨蛋")));
        DlsEngine.Evaluation excluded = DlsEngine.evaluate(compiled, List.of(message("m2", 1, "agent", "你说笨蛋这个词不礼貌")));

        assertThat(hit.hit()).isTrue();
        assertThat(hit.evidence()).extracting(DlsEngine.Hit::definition).contains("rule_abuse");
        assertThat(excluded.hit()).isFalse();
    }

    @Test
    void orderedOperatorRequiresLaterMessage() {
        DlsRuleDocument document = new DlsRuleDocument("1.0", null, List.of(
                definition("slot_need", "SLOT", "我要投诉", "all"),
                definition("slot_phone", "SLOT", "客服电话", "all"),
                definition("rule_need", "RULE", "[slot_need]", "user"),
                definition("rule_guide", "RULE", "[slot_phone]", "agent")
        ), "引导回电", "[rule_need]%[rule_guide]");
        DlsEngine.Compiled compiled = DlsEngine.compile(document);

        assertThat(DlsEngine.evaluate(compiled, List.of(
                message("m1", 1, "user", "我要投诉"), message("m2", 2, "agent", "请拨打客服电话"))).hit()).isTrue();
        assertThat(DlsEngine.evaluate(compiled, List.of(
                message("m1", 1, "agent", "请拨打客服电话"), message("m2", 2, "user", "我要投诉"))).hit()).isFalse();
    }

    @Test
    void orderedNegativeStageExcludesLaterReminder() {
        DlsRuleDocument document = new DlsRuleDocument("1.0", null, List.of(
                definition("slot_borrow", "SLOT", "再借一笔", "all"),
                definition("slot_remind", "SLOT", "不得以贷养贷", "all"),
                definition("rule_borrow", "RULE", "[slot_borrow]", "agent"),
                definition("rule_remind", "RULE", "[slot_remind]", "agent")
        ), "以贷养贷", "[rule_borrow]%![rule_remind]");
        DlsEngine.Compiled compiled = DlsEngine.compile(document);

        assertThat(DlsEngine.evaluate(compiled, List.of(message("m1", 1, "agent", "建议再借一笔"))).hit()).isTrue();
        assertThat(DlsEngine.evaluate(compiled, List.of(message("m1", 1, "agent", "建议再借一笔"),
                message("m2", 2, "agent", "但是不得以贷养贷"))).hit()).isFalse();
    }

    @Test
    void rejectsMissingReferences() {
        DlsRuleDocument document = new DlsRuleDocument("1.0", null,
                List.of(definition("rule_bad", "RULE", "[slot_missing_a];[slot_missing_b]", "all")), "规则", "[rule_bad]");

        assertThatThrownBy(() -> DlsEngine.compile(document))
                .hasMessageContaining("slot_missing_a")
                .hasMessageContaining("slot_missing_b");
    }

    private DlsRuleDocument.Definition definition(String name, String kind, String expression, String role) {
        return new DlsRuleDocument.Definition(name, kind, expression, role);
    }

    private ConversationMessage message(String id, int sequence, String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id); message.setSequenceNo(sequence); message.setSpeakerRole(role); message.setContent(content);
        return message;
    }
}
