package io.github.opensabre.iqc.template;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.QualityRuleService;
import io.github.opensabre.iqc.rule.model.QualityRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Owns built-in IQC templates and idempotently materializes their rules as reviewable drafts. */
@Service
public class IqcTemplateService {
    private static final List<QualityTemplate> TEMPLATES = List.of(
            new QualityTemplate("NEGATIVE_COMMON_V1", "通用负面行为规则 V1", "通用负面质检",
                    "覆盖服务态度、销售合规、隐私与风险披露等常见负面行为。", List.of(
                    llm("NEG_INSULT_ABUSE", "辱骂与人身攻击", "判断坐席是否辱骂、嘲讽、贬低客户或使用侮辱性称呼。", "HIGH", 40, true),
                    llm("NEG_THREAT_INTIMIDATION", "威胁与恐吓", "判断坐席是否以人身、财产、征信、曝光等后果威胁或恐吓客户。", "HIGH", 50, true),
                    llm("NEG_FALSE_PROMISE", "虚假或越权承诺", "判断坐席是否承诺无法确认的返现、收益、审批结果、处理时效或特殊权益。", "HIGH", 40, true),
                    llm("NEG_EXAGGERATED_CLAIM", "夸大与绝对化宣传", "判断坐席是否使用百分百、绝对安全、稳赚、保证成功等无依据的绝对化表述。", "HIGH", 35, false),
                    llm("NEG_INDUCED_PURCHASE", "诱导或强迫消费", "判断坐席是否利用虚假稀缺、隐瞒条件、持续施压等方式诱导客户购买。", "HIGH", 35, false),
                    keyword("NEG_PRIVATE_DIVERSION", "私下引流与绕平台交易", "微信|加我好友|私下转账|个人账户|线下付款|扫码给我", "发现要求客户绕开官方渠道联系或付款。", "HIGH", 50, true),
                    llm("NEG_PRIVACY_DISCLOSURE", "隐私泄露与不当索取", "判断坐席是否泄露他人隐私，或在非必要场景索取身份证、银行卡、验证码、密码等敏感信息。", "HIGH", 50, true),
                    llm("NEG_SHIRK_REFUSAL", "推诿、敷衍与无理拒绝", "判断坐席是否未解释原因便推诿责任、拒绝处理，或要求客户自行反复联系其他渠道。", "MEDIUM", 25, false),
                    keyword("NEG_PASSIVE_SERVICE", "消极服务用语", "不知道|不清楚|没办法|随便你|爱信不信|不归我管|你自己看", "发现明显消极、冷漠或终止沟通的话术。", "MEDIUM", 20, false),
                    llm("NEG_DISCRIMINATION", "歧视性或偏见表达", "判断坐席是否针对地域、性别、年龄、职业、民族、健康状况等作出歧视或贬损表达。", "HIGH", 45, true),
                    llm("NEG_UNAUTHORIZED_DISCOUNT", "未经授权的优惠减免", "判断坐席是否未经授权承诺费用减免、退款、利率调整或账务处理结果。", "HIGH", 40, true),
                    llm("NEG_RISK_CONCEALMENT", "隐瞒重要条件与风险", "判断坐席是否隐瞒费用、限制条件、违约后果、产品风险或其他影响客户决策的重要信息。", "HIGH", 50, true)
            )),
            new QualityTemplate("COLLECTION_BASIC", "催收合规基础模板", "催收场景", "关注优惠承诺、还款提醒和沟通规范。", List.of(
                    llm("COLLECTION_DISCOUNT", "优惠/减免承诺", "检查是否存在未经授权的优惠或减免承诺。", "HIGH", 40, true),
                    llm("COLLECTION_REPAYMENT", "还款提醒", "检查是否明确、礼貌地提醒还款安排。", "MEDIUM", 15, false),
                    llm("COLLECTION_TONE", "沟通规范", "检查催收沟通是否保持克制、清晰和合规。", "HIGH", 30, false))),
            new QualityTemplate("SALES_SERVICE", "销售服务标准模板", "销售场景", "关注需求识别、产品介绍和风险提示。", List.of(
                    llm("SALES_DISCOVERY", "需求识别", "检查是否了解客户需求后再进行推荐。", "MEDIUM", 15, false),
                    llm("SALES_INTRODUCTION", "产品介绍", "检查产品关键信息是否准确、完整。", "HIGH", 30, false),
                    llm("SALES_RISK", "风险提示", "检查是否完成必要的风险提示和确认。", "HIGH", 40, true))),
            new QualityTemplate("CUSTOMER_SERVICE", "客服服务质量模板", "客服场景", "关注礼貌用语、问题解决和服务闭环。", List.of(
                    llm("SERVICE_GREETING", "礼貌沟通", "检查是否使用清晰、礼貌的服务用语。", "LOW", 5, false),
                    llm("SERVICE_SOLUTION", "问题解决", "检查是否正面回应客户问题并给出解决方案。", "MEDIUM", 20, false),
                    llm("SERVICE_CLOSURE", "服务闭环", "检查是否确认客户诉求已处理或明确后续安排。", "MEDIUM", 15, false)))
    );

    private final QualityRuleService ruleService;

    public IqcTemplateService(QualityRuleService ruleService) { this.ruleService = ruleService; }
    public List<QualityTemplate> list() { return TEMPLATES; }

    /** Creates missing rules only; existing codes are returned unchanged so the operation is safely repeatable. */
    @Transactional
    public MaterializationResult materialize(String templateId) {
        QualityTemplate template = TEMPLATES.stream().filter(item -> item.id().equals(templateId)).findFirst()
                .orElseThrow(() -> IqcException.notFound("质检模板不存在: " + templateId));
        List<QualityRule> rules = new ArrayList<>();
        int created = 0;
        for (TemplateRule definition : template.rules()) {
            QualityRule existing = ruleService.findByCode(definition.code());
            if (existing != null) { rules.add(existing); continue; }
            rules.add(ruleService.create(definition.name(), definition.code(), template.type(), definition.ruleType(),
                    definition.targetRole(), definition.expression(), definition.description(), definition.deduction(),
                    definition.riskLevel(), definition.veto()));
            created++;
        }
        return new MaterializationResult(template.id(), template.rules().size(), created, template.rules().size() - created, rules);
    }

    private static TemplateRule llm(String code, String name, String description, String risk, int deduction, boolean veto) {
        return new TemplateRule(code, name, description, risk, "LLM", "agent", description, deduction, veto);
    }
    private static TemplateRule keyword(String code, String name, String expression, String description, String risk, int deduction, boolean veto) {
        return new TemplateRule(code, name, description, risk, "KEYWORD", "agent", expression, deduction, veto);
    }

    public record QualityTemplate(String id, String name, String type, String description, List<TemplateRule> rules) { }
    public record TemplateRule(String code, String name, String description, String riskLevel, String ruleType,
                               String targetRole, String expression, int deduction, boolean veto) { }
    public record MaterializationResult(String templateId, int total, int created, int existing, List<QualityRule> rules) { }
}
