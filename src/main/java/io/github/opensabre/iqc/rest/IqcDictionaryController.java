package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.dictionary.DictionaryItem;
import io.github.opensabre.governance.dictionary.DictionaryService;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/iqc/dictionaries")
public class IqcDictionaryController {
    private final DictionaryService dictionaryService;

    public IqcDictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:dictionary:view", name = "查看 IQC 字典", type = "iqc", description = "读取 IQC 字典选项")
    @RateLimit(sceneCode = "iqc-dictionary-query", maxCount = 60, period = 60)
    public Map<String, List<DictionaryItem>> items(@RequestParam String codes) {
        Map<String, List<DictionaryItem>> result = new LinkedHashMap<>();
        Arrays.stream(codes.split(","))
                .map(String::trim).filter(code -> !code.isBlank()).distinct()
                .forEach(code -> result.put(code, dictionaryService.items(code)));
        return result;
    }
}
