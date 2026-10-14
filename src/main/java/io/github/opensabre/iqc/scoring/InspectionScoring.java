package io.github.opensabre.iqc.scoring;

import io.github.opensabre.iqc.governance.IqcException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** Deterministic V2 scoring over business item decisions, independent of detector rule fields. */
public final class InspectionScoring {
    public static final String POLICY_VERSION = "iqc-score-v2";

    public static final String ADVANCED_VERSION = "iqc-score-v3";

    private InspectionScoring() { }

    public enum Mode { DEDUCTION, POINTS }
    public enum ItemStatus { PASS, FAIL, NOT_APPLICABLE, REVIEW_REQUIRED, ERROR, NOT_EVALUATED }
    public enum ScoreStatus { FINAL, PENDING, NOT_APPLICABLE }
    public enum Conclusion { QUALIFIED, UNQUALIFIED, PENDING, NOT_APPLICABLE }

    /** One scoring responsibility; detector reuse never creates another entry implicitly. */
    public record Item(String itemCode, int points, boolean veto) { }

    /** Frozen policy owned by a scheme version, not by a detection rule or Agent. */
    public record Policy(String version, Mode mode, int baseScore, int passingScore, List<Item> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) Advanced advanced) {
        public Policy(String version,Mode mode,int baseScore,int passingScore,List<Item> items) {
            this(version,mode,baseScore,passingScore,items,null);
        }
        public void validateReferences(Set<String> findings,Set<String> items,io.github.opensabre.iqc.label.FindingLabelPolicy labels) {
            if(advanced==null)return;
            var addresses=new HashSet<String>();
            if(labels!=null)labels.bindings().forEach(binding->addresses.add(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(binding)));
            advanced.items().forEach(item->{if(item.appliesWhen()!=null)ActionPolicy.validateDecisionInputs(item.appliesWhen(),findings,items,addresses,false);});
        }
        public Policy {
            if (!(POLICY_VERSION.equals(version) && advanced==null || ADVANCED_VERSION.equals(version) && advanced!=null)) throw IqcException.invalidArgument("不支持的评分政策版本");
            if (mode == null) throw IqcException.invalidArgument("必须选择评分方式");
            if (baseScore < 1 || baseScore > 100) throw IqcException.invalidArgument("基础分必须在 1 到 100 之间");
            int maximum = advanced!=null || mode == Mode.POINTS ? 100 : baseScore;
            if (passingScore < 0 || passingScore > maximum) throw IqcException.invalidArgument("合格分数超出评分范围");
            if (items == null) throw IqcException.invalidArgument("评分项目不能为空，仅打标方案请使用空列表");
            var codes = new HashSet<String>();
            for (Item item : items) {
                if (item == null || item.itemCode() == null || item.itemCode().isBlank()
                        || !item.itemCode().equals(item.itemCode().trim()) || !codes.add(item.itemCode()))
                    throw IqcException.invalidArgument("评分项目编码不能为空、重复或包含首尾空格");
                if (item.points() < 0 || item.points() > 100 || (mode == Mode.POINTS && item.points() == 0))
                    throw IqcException.invalidArgument("项目分值超出范围: " + item.itemCode());
            }
            items = List.copyOf(items);
            if(advanced!=null) {
                if(items.size()>200)throw IqcException.invalidArgument("复杂评分最多200项");
                if(BigDecimal.valueOf(passingScore).compareTo(decimal(advanced.lowerBound()))<0 || BigDecimal.valueOf(passingScore).compareTo(decimal(advanced.upperBound()))>0)
                    throw IqcException.invalidArgument("合格线须在评分上下限内");
                for(var item:advanced.items())if(!codes.contains(item.itemCode()))throw IqcException.invalidArgument("评分扩展引用未知项目");
            }
        }
    }

    public record Decision(String itemCode, ItemStatus status) { }

    /** Contribution is deducted points in DEDUCTION mode and earned points in POINTS mode. */
    public record Line(String itemCode, ItemStatus status, int configuredPoints, Integer contribution, boolean vetoTriggered) { }
    public record Coverage(int expected, int completed, int notApplicable, int pending) { }
    public record Result(String policyVersion, Mode mode, ScoreStatus scoreStatus, BigDecimal finalScore,
                         Conclusion conclusion, boolean vetoTriggered, List<Line> lines, Coverage coverage,
                         @JsonInclude(JsonInclude.Include.NON_NULL) AdvancedResult advanced) {
        public Result(String policyVersion,Mode mode,ScoreStatus scoreStatus,BigDecimal finalScore,Conclusion conclusion,
                boolean vetoTriggered,List<Line> lines,Coverage coverage) {
            this(policyVersion,mode,scoreStatus,finalScore,conclusion,vetoTriggered,lines,coverage,null);
        }
    }

    /** Scores exactly one decision per item; missing decisions stay pending instead of becoming passes. */
    public static Result evaluate(Policy policy, List<Decision> decisions) {
        return evaluate(policy,decisions,null);
    }
    public static Result evaluate(Policy policy,List<Decision> decisions,ActionPolicy.Context context) {
        if (policy == null || decisions == null) throw IqcException.invalidArgument("评分政策和检查结果不能为空");
        if(policy.advanced()!=null)return evaluateAdvanced(policy,decisions,context);
        Map<String, ItemStatus> statuses = new HashMap<>();
        var expected = new HashSet<String>();
        policy.items().forEach(item -> expected.add(item.itemCode()));
        for (Decision decision : decisions) {
            if (decision == null || decision.itemCode() == null || decision.status() == null
                    || !expected.contains(decision.itemCode())) throw IqcException.invalidArgument("评分结果引用了未知项目");
            if (statuses.putIfAbsent(decision.itemCode(), decision.status()) != null)
                throw IqcException.invalidArgument("同一质检项只能提交一个最终结论: " + decision.itemCode());
        }
        List<Line> lines = new ArrayList<>();
        long total = 0, denominator = 0;
        int completed = 0, notApplicable = 0, pending = 0;
        boolean veto = false;
        for (Item item : policy.items()) {
            ItemStatus status = statuses.getOrDefault(item.itemCode(), ItemStatus.NOT_EVALUATED);
            Integer contribution = null;
            boolean triggered = status == ItemStatus.FAIL && item.veto();
            veto |= triggered;
            switch (status) {
                case PASS, FAIL -> {
                    completed++;
                    denominator += item.points();
                    contribution = policy.mode() == Mode.DEDUCTION
                            ? (status == ItemStatus.FAIL ? item.points() : 0)
                            : (status == ItemStatus.PASS ? item.points() : 0);
                    total += contribution;
                }
                case NOT_APPLICABLE -> { notApplicable++; contribution = 0; }
                default -> pending++;
            }
            lines.add(new Line(item.itemCode(), status, item.points(), contribution, triggered));
        }
        ScoreStatus scoreStatus = pending > 0 ? ScoreStatus.PENDING
                : completed == 0 ? ScoreStatus.NOT_APPLICABLE : ScoreStatus.FINAL;
        BigDecimal score = null;
        if (scoreStatus == ScoreStatus.FINAL) {
            score = veto ? BigDecimal.ZERO : policy.mode() == Mode.DEDUCTION
                    ? BigDecimal.valueOf(Math.max(0L, policy.baseScore() - total))
                    : BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        }
        Conclusion conclusion = veto ? Conclusion.UNQUALIFIED
                : scoreStatus == ScoreStatus.PENDING ? Conclusion.PENDING
                : scoreStatus == ScoreStatus.NOT_APPLICABLE ? Conclusion.NOT_APPLICABLE
                : score.compareTo(BigDecimal.valueOf(policy.passingScore())) >= 0 ? Conclusion.QUALIFIED : Conclusion.UNQUALIFIED;
        return new Result(policy.version(), policy.mode(), scoreStatus, score, conclusion, veto, List.copyOf(lines),
                new Coverage(policy.items().size(), completed, notApplicable, pending));
    }
    public record ItemAdjustment(String itemCode,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String weight,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String passAdjustment,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String failAdjustment,
            String dimensionCode,ActionPolicy.Condition appliesWhen) {
        public ItemAdjustment {
            identity(itemCode);range(weight,BigDecimal.ZERO,BigDecimal.valueOf(100),false);
            range(passAdjustment,BigDecimal.valueOf(-100),BigDecimal.valueOf(100),true);
            range(failAdjustment,BigDecimal.valueOf(-100),BigDecimal.valueOf(100),true);
            if(dimensionCode!=null)identity(dimensionCode);
            if(appliesWhen!=null)ActionPolicy.validateDecisionInputs(appliesWhen,null,null,null,false);
        }
    }
    public record Dimension(String code,int baseScore,int passingScore,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String lowerBound,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String upperBound) {
        public Dimension {identity(code);bounds(lowerBound,upperBound);if(baseScore<1 || baseScore>100
                || BigDecimal.valueOf(passingScore).compareTo(decimal(lowerBound))<0 || BigDecimal.valueOf(passingScore).compareTo(decimal(upperBound))>0)
            throw IqcException.invalidArgument("维度基础分或合格线无效");}
    }
    public record Grade(String code,@JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String minimumScore) {
        public Grade {identity(code);range(minimumScore,BigDecimal.ZERO,BigDecimal.valueOf(100),true);}
    }
    public record Advanced(String schemaVersion,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String lowerBound,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String upperBound,
            List<ItemAdjustment> items,List<Dimension> dimensions,List<Grade> grades) {
        public static final String SCHEMA="iqc-score-advanced-v1";
        public Advanced {
            if(!SCHEMA.equals(schemaVersion))throw IqcException.invalidArgument("复杂评分协议无效");bounds(lowerBound,upperBound);
            items=List.copyOf(Objects.requireNonNull(items));dimensions=List.copyOf(Objects.requireNonNull(dimensions));grades=List.copyOf(Objects.requireNonNull(grades));
            if(items.size()>200 || dimensions.size()>100 || grades.size()>100)throw IqcException.invalidArgument("复杂评分配置超出上限");
            var ids=new HashSet<String>();for(var dimension:dimensions)if(!ids.add(dimension.code()))throw IqcException.invalidArgument("维度编码重复");
            var seen=new HashSet<String>();int nodes=0;
            for(var item:items){if(!seen.add(item.itemCode()) || item.dimensionCode()!=null && !ids.contains(item.dimensionCode()))throw IqcException.invalidArgument("项目扩展重复或维度未声明");if(item.appliesWhen()!=null)nodes+=conditionCount(item.appliesWhen());}
            if(nodes>1000)throw IqcException.invalidArgument("评分条件最多1000个节点");
            ids.clear();BigDecimal previous=null;
            for(var grade:grades){var minimum=decimal(grade.minimumScore());if(!ids.add(grade.code()) || previous!=null && minimum.compareTo(previous)>=0)throw IqcException.invalidArgument("等级须按下界严格降序且编码唯一");previous=minimum;}
            if(previous!=null && previous.signum()!=0)throw IqcException.invalidArgument("等级映射须覆盖0分");
        }
    }
    public record WeightedLine(String itemCode,ItemStatus originalStatus,ItemStatus status,ActionPolicy.Match applicability,
            int configuredPoints,BigDecimal weight,BigDecimal baseContribution,BigDecimal adjustment,boolean vetoTriggered,String dimensionCode) { }
    public record DimensionResult(String code,ScoreStatus scoreStatus,BigDecimal rawScore,BigDecimal finalScore,
            Conclusion conclusion,boolean vetoTriggered,Coverage coverage,BigDecimal rawNumerator,BigDecimal rawDenominator) { }
    public record AdvancedResult(String schemaVersion,BigDecimal rawScore,String grade,List<WeightedLine> lines,List<DimensionResult> dimensions,BigDecimal rawNumerator,BigDecimal rawDenominator) {
        public AdvancedResult {lines=List.copyOf(lines);dimensions=List.copyOf(dimensions);}
    }
    private record Aggregate(ScoreStatus status,BigDecimal raw,BigDecimal score,Conclusion conclusion,boolean veto,Coverage coverage,BigDecimal numerator,BigDecimal denominator) { }
    private static Result evaluateAdvanced(Policy policy,List<Decision> decisions,ActionPolicy.Context supplied) {
        var statuses=new HashMap<String,ItemStatus>();var expected=new HashSet<String>();policy.items().forEach(item->expected.add(item.itemCode()));
        for(var decision:decisions)if(decision==null || decision.status()==null || !expected.contains(decision.itemCode()) || statuses.putIfAbsent(decision.itemCode(),decision.status())!=null)
            throw IqcException.invalidArgument("复杂评分结论引用未知或重复项目");
        var context=supplied==null?new ActionPolicy.Context(Map.of(),statuses,ScoreStatus.PENDING,null,null,null):
                new ActionPolicy.Context(supplied.findings(),statuses,ScoreStatus.PENDING,null,null,null,supplied.labels(),supplied.businessContext());
        var extensions=new HashMap<String,ItemAdjustment>();policy.advanced().items().forEach(item->extensions.put(item.itemCode(),item));
        var weighted=new ArrayList<WeightedLine>();var lines=new ArrayList<Line>();
        for(var item:policy.items()) {
            var extension=extensions.get(item.itemCode());var original=statuses.getOrDefault(item.itemCode(),ItemStatus.NOT_EVALUATED);
            var match=extension==null || extension.appliesWhen()==null?ActionPolicy.Match.MATCH:ActionPolicy.evaluate(extension.appliesWhen(),context);
            var status=original==ItemStatus.NOT_APPLICABLE?original:match==ActionPolicy.Match.NO_MATCH?ItemStatus.NOT_APPLICABLE:match==ActionPolicy.Match.UNKNOWN?ItemStatus.REVIEW_REQUIRED:original;
            var weight=extension==null?BigDecimal.ONE:decimal(extension.weight());boolean triggered=status==ItemStatus.FAIL && item.veto();
            BigDecimal contribution=null,adjustment=null;Integer base=null;
            if(status==ItemStatus.PASS || status==ItemStatus.FAIL) {
                base=policy.mode()==Mode.DEDUCTION?(status==ItemStatus.FAIL?item.points():0):(status==ItemStatus.PASS?item.points():0);
                contribution=BigDecimal.valueOf(policy.mode()==Mode.DEDUCTION?-base:base).multiply(weight);
                adjustment=(extension==null?BigDecimal.ZERO:decimal(status==ItemStatus.PASS?extension.passAdjustment():extension.failAdjustment())).multiply(weight);
            } else if(status==ItemStatus.NOT_APPLICABLE){base=0;contribution=BigDecimal.ZERO;adjustment=BigDecimal.ZERO;}
            weighted.add(new WeightedLine(item.itemCode(),original,status,match,item.points(),weight,contribution,adjustment,triggered,extension==null?null:extension.dimensionCode()));
            lines.add(new Line(item.itemCode(),status,item.points(),base,triggered));
        }
        var overall=aggregate(policy.mode(),policy.baseScore(),policy.passingScore(),policy.advanced().lowerBound(),policy.advanced().upperBound(),weighted);
        var dimensions=new ArrayList<DimensionResult>();
        for(var dimension:policy.advanced().dimensions()) {
            var aggregate=aggregate(policy.mode(),dimension.baseScore(),dimension.passingScore(),dimension.lowerBound(),dimension.upperBound(),weighted.stream().filter(line->dimension.code().equals(line.dimensionCode())).toList());
            dimensions.add(new DimensionResult(dimension.code(),aggregate.status(),aggregate.raw(),aggregate.score(),aggregate.conclusion(),aggregate.veto(),aggregate.coverage(),aggregate.numerator(),aggregate.denominator()));
        }
        String grade=null;if(overall.status()==ScoreStatus.FINAL)for(var configured:policy.advanced().grades())if(overall.score().compareTo(decimal(configured.minimumScore()))>=0){grade=configured.code();break;}
        return new Result(policy.version(),policy.mode(),overall.status(),overall.score(),overall.conclusion(),overall.veto(),lines,overall.coverage(),
                new AdvancedResult("iqc-score-result-v3",overall.raw(),grade,weighted,dimensions,overall.numerator(),overall.denominator()));
    }
    private static Aggregate aggregate(Mode mode,int baseScore,int passingScore,String lower,String upper,List<WeightedLine> lines) {
        BigDecimal total=BigDecimal.ZERO,adjustment=BigDecimal.ZERO,denominator=BigDecimal.ZERO;int completed=0,na=0,pending=0;boolean veto=false;
        for(var line:lines){veto|=line.vetoTriggered();switch(line.status()){
            case PASS,FAIL -> {completed++;total=total.add(line.baseContribution());adjustment=adjustment.add(line.adjustment());denominator=denominator.add(BigDecimal.valueOf(line.configuredPoints()).multiply(line.weight()));}
            case NOT_APPLICABLE -> na++;default -> pending++;
        }}
        var status=pending>0?ScoreStatus.PENDING:completed==0?ScoreStatus.NOT_APPLICABLE:ScoreStatus.FINAL;BigDecimal raw=null,score=null;
        BigDecimal numerator=null,divisor=null;
        if(status==ScoreStatus.FINAL){
            divisor=mode==Mode.DEDUCTION?BigDecimal.ONE:denominator;
            numerator=mode==Mode.DEDUCTION?BigDecimal.valueOf(baseScore).add(total).add(adjustment):total.multiply(BigDecimal.valueOf(100)).add(adjustment.multiply(divisor));
            raw=mode==Mode.DEDUCTION?numerator:numerator.divide(divisor,38,RoundingMode.HALF_UP);
            // Compare and round the exact rational once; diagnostic rawScore never drives the final decision.
            score=veto?BigDecimal.ZERO:numerator.compareTo(decimal(lower).multiply(divisor))<0?decimal(lower).setScale(2):
                    numerator.compareTo(decimal(upper).multiply(divisor))>0?decimal(upper).setScale(2):numerator.divide(divisor,2,RoundingMode.HALF_UP);
        }
        var conclusion=veto?Conclusion.UNQUALIFIED:status==ScoreStatus.PENDING?Conclusion.PENDING:status==ScoreStatus.NOT_APPLICABLE?Conclusion.NOT_APPLICABLE:
                score.compareTo(BigDecimal.valueOf(passingScore))>=0?Conclusion.QUALIFIED:Conclusion.UNQUALIFIED;
        return new Aggregate(status,raw,score,conclusion,veto,new Coverage(lines.size(),completed,na,pending),numerator,divisor);
    }
    private static BigDecimal decimal(String value){new PipelineRelease.NumericPredicate(io.github.opensabre.iqc.pipeline.PipelineDefinition.Operator.EQUALS,value);return new BigDecimal(value);}
    private static void range(String value,BigDecimal lower,BigDecimal upper,boolean inclusive){var decimal=decimal(value);if((inclusive?decimal.compareTo(lower)<0:decimal.compareTo(lower)<=0) || decimal.compareTo(upper)>0)throw IqcException.invalidArgument("评分数值超出范围");}
    private static void bounds(String lower,String upper){range(lower,BigDecimal.ZERO,BigDecimal.valueOf(100),true);range(upper,BigDecimal.ZERO,BigDecimal.valueOf(100),true);if(decimal(lower).scale()>2 || decimal(upper).scale()>2 || decimal(lower).compareTo(decimal(upper))>0)throw IqcException.invalidArgument("评分上下限最多2位小数且顺序须有效");}
    private static void identity(String code){if(code==null || !code.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw IqcException.invalidArgument("评分编码无效");}
    private static int conditionCount(ActionPolicy.Condition condition){int count=1;for(var child:condition.children())count+=conditionCount(child);return count;}

}
