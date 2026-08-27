package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

@OpenSabreDictionary(code = "iqc_task_status", name = "IQC 质检任务状态")
public enum IqcTaskStatus implements DictionaryEnum {
    CREATED("CREATED", "已创建", "processing"),
    SCHEDULED("SCHEDULED", "等待调度", "processing"),
    MATERIALIZING("MATERIALIZING", "正在选取会话", "processing"),
    QUEUED("QUEUED", "排队中", "processing"),
    RUNNING("RUNNING", "执行中", "processing"),
    SUCCEEDED("SUCCEEDED", "已完成", "success"),
    NO_DATA("NO_DATA", "无匹配数据", "default"),
    PARTIAL_FAILED("PARTIAL_FAILED", "部分失败", "warning"),
    FAILED("FAILED", "失败", "error"),
    CANCELLED("CANCELLED", "已取消", "default");

    private final String value;
    private final String label;
    private final String tagType;

    IqcTaskStatus(String value, String label, String tagType) {
        this.value = value;
        this.label = label;
        this.tagType = tagType;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String tagType() { return tagType; }
}
