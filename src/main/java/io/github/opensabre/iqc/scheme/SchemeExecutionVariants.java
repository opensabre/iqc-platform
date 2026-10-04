package io.github.opensabre.iqc.scheme;

import io.github.opensabre.iqc.governance.IqcException;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite expert-approved route alternatives; never an arbitrary task-side override or scoring policy. */
public record SchemeExecutionVariants(String recommendedCode, List<Variant> variants) {
    /** Each alternative must cover every business item and explain its coverage and operational tradeoff. */
    public record Variant(String code, String name, String coverage, String cost,
                          Map<String, SchemeDefinition.Execution> routes) {
        public Variant {
            if (code == null || !code.matches("[A-Za-z0-9_-]{1,64}") || !text(name, 100)
                    || !text(coverage, 1000) || !text(cost, 1000))
                throw IqcException.invalidArgument("策略变体必须包含有效编码、名称、覆盖与成本说明");
            if (routes == null || routes.isEmpty() || routes.size() > 200
                    || routes.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || !entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || entry.getValue() == null))
                throw IqcException.invalidArgument("策略变体必须为每个质检项指定明确路线");
            // The frozen release hash and trial idempotency fingerprint include this map.
            // Preserve persisted JSON order across JVM restarts; Map.copyOf does not guarantee iteration order.
            routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        }
    }

    public SchemeExecutionVariants {
        if (variants == null || variants.isEmpty() || variants.size() > 20)
            throw IqcException.invalidArgument("允许策略变体必须在 1 到 20 个之间");
        var codes = new HashSet<String>();
        for (var variant : variants) {
            if (variant == null || !codes.add(variant.code()))
                throw IqcException.invalidArgument("策略变体编码无效或重复");
        }
        if (recommendedCode == null || !codes.contains(recommendedCode))
            throw IqcException.invalidArgument("推荐策略必须属于允许策略变体");
        variants = List.copyOf(variants);
    }

    /** Resolve an omitted selection to the recommendation; unknown or blank selections never fall back. */
    public Variant select(String code) {
        String selected = code == null ? recommendedCode : code;
        return variants.stream().filter(variant -> variant.code().equals(selected)).findFirst()
                .orElseThrow(() -> IqcException.invalidArgument("所选策略不在模板允许变体中"));
    }

    /** Validate all alternatives, not only the selected one, using the existing item protocol checks. */
    public void validate(SchemeDefinition definition) {
        for (var variant : variants) expand(definition, variant);
    }

    /** Expand approved routes without changing business item identity, detector, Agent, labels or scoring. */
    public SchemeDefinition expand(SchemeDefinition definition, String code) {
        validate(definition);
        return expand(definition, select(code));
    }

    private SchemeDefinition expand(SchemeDefinition definition, Variant variant) {
        var itemCodes = new HashSet<String>();
        definition.items().forEach(item -> itemCodes.add(item.itemCode()));
        if (!variant.routes().keySet().equals(itemCodes))
            throw IqcException.invalidArgument("策略变体必须完整覆盖模板质检项，不能增加或省略项目");
        var items = definition.items().stream().map(item -> new SchemeDefinition.Item(
                item.itemCode(), item.name(), item.rule(), item.hitMeaning(), item.appliesWhen(),
                item.inputScope(), variant.routes().get(item.itemCode()))).toList();
        return new SchemeDefinition(definition.schemaVersion(), items, definition.agent(), definition.scoring(),
                definition.runLimits(), definition.labels());
    }

    private static boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }
}
