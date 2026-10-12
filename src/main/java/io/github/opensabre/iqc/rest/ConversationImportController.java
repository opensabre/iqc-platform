package io.github.opensabre.iqc.rest;

import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.conversation.ConversationParseResult;
import io.github.opensabre.iqc.conversation.ConversationImportService;
import io.github.opensabre.iqc.conversation.ApiConversationInput;
import io.github.opensabre.iqc.conversation.ConversationApiIngestService;
import io.github.opensabre.iqc.conversation.ConversationUploadProperties;
import io.github.opensabre.iqc.conversation.TxtConversationParser;
import io.github.opensabre.iqc.governance.IqcException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.opensabre.iqc.conversation.ConversationMetadata;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/iqc/conversations")
public class ConversationImportController {

    private final TxtConversationParser parser;
    private final ConversationImportService importService;
    private final UsageCounterRecorder usageCounterRecorder;
    private final ConversationUploadProperties uploadProperties;
    @org.springframework.beans.factory.annotation.Autowired
    private ConversationApiIngestService apiIngestService;

    public ConversationImportController(TxtConversationParser parser, ConversationImportService importService,
                                        UsageCounterRecorder usageCounterRecorder,
                                        ConversationUploadProperties uploadProperties) {
        this.parser = parser;
        this.importService = importService;
        this.usageCounterRecorder = usageCounterRecorder;
        this.uploadProperties = uploadProperties;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourcePermission(code = "iqc:conversation:upload", name = "上传会话", type = "iqc", description = "上传并解析 txt 会话")
    @Audit(operationType = OperationType.IMPORT, description = "导入 IQC txt 会话", module = "IQC_CONVERSATION", request = false)
    @RateLimit(sceneCode = "iqc-conversation-import", maxCount = 30, period = 60)
    public Map<String, Object> importConversation(@RequestPart("file") MultipartFile file,
                                                  @RequestPart(value = "metadata", required = false) ConversationMetadata metadata) throws IOException {
        return importFile(file, null, metadata);
    }

    /** Convenience entry used by existing callers that do not provide metadata. */
    public Map<String, Object> importConversation(MultipartFile file) throws IOException { return importConversation(file, null); }

    /** Imports several TXT files as one traceable business batch. */
    @PostMapping(value = "/batch-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourcePermission(code = "iqc:conversation:upload", name = "批量上传会话", type = "iqc", description = "批量上传并解析 txt 会话")
    @Audit(operationType = OperationType.IMPORT, description = "批量导入 IQC txt 会话", module = "IQC_CONVERSATION", request = false)
    @RateLimit(sceneCode = "iqc-conversation-batch-import", maxCount = 10, period = 60)
    public Map<String, Object> importBatch(@RequestPart("files") List<MultipartFile> files,
                                           @RequestPart(value = "metadata", required = false) ConversationMetadata metadata) throws IOException {
        if (files == null || files.isEmpty() || files.size() > 100) {
            throw IqcException.invalidArgument("每批请选择 1 至 100 个会话文件");
        }
        String batchNo = "CB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        for (MultipartFile file : files) {
            try {
                Map<String, Object> result = importFile(file, batchNo, metadata);
                results.add(result);
                successCount++;
            } catch (RuntimeException | IOException exception) {
                results.add(Map.of("fileName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                        "status", "FAILED", "reason", exception.getMessage() == null ? "导入失败" : exception.getMessage()));
            }
        }
        return Map.of("batchNo", batchNo, "totalCount", files.size(), "successCount", successCount,
                "failureCount", files.size() - successCount, "items", results);
    }

    /** Imports TXT entries from one ZIP archive as a single conversation batch. */
    @PostMapping(value = "/zip-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourcePermission(code = "iqc:conversation:upload", name = "ZIP 批量上传会话", type = "iqc", description = "从 ZIP 包批量导入 txt 会话")
    @Audit(operationType = OperationType.IMPORT, description = "ZIP 批量导入 IQC 会话", module = "IQC_CONVERSATION", request = false)
    @RateLimit(sceneCode = "iqc-conversation-zip-import", maxCount = 10, period = 60)
    public Map<String, Object> importZip(@RequestPart("file") MultipartFile archive,
                                         @RequestPart(value = "metadata", required = false) ConversationMetadata metadata) throws IOException {
        String archiveName = archive.getOriginalFilename() == null ? "conversations.zip" : archive.getOriginalFilename();
        if (archive.isEmpty() || !archiveName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw IqcException.invalidArgument("请选择非空 ZIP 文件");
        }
        String batchNo = newBatchNo();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(archive.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX/")) continue;
                if (results.size() >= 100) throw IqcException.invalidArgument("ZIP 内最多包含 100 个 TXT 文件");
                String fileName = entry.getName().replace('\\', '/');
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    results.add(Map.of("fileName", fileName, "status", "SKIPPED", "reason", "不是 TXT 文件"));
                    continue;
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    content.write(buffer, 0, read);
                    totalBytes += read;
                    if (content.size() > uploadProperties.getMaxFileSizeBytes() || totalBytes > 100L * 1024 * 1024) {
                        throw IqcException.invalidArgument("ZIP 解压内容超过限制（单文件 20MB、总计 100MB）");
                    }
                }
                try {
                    results.add(importContent(fileName, content.toByteArray(), batchNo, metadata));
                    successCount++;
                } catch (RuntimeException exception) {
                    results.add(Map.of("fileName", fileName, "status", "FAILED",
                            "reason", exception.getMessage() == null ? "导入失败" : exception.getMessage()));
                }
            }
        }
        if (results.isEmpty()) throw IqcException.invalidArgument("ZIP 内未找到可处理的文件");
        return Map.of("batchNo", batchNo, "totalCount", results.size(), "successCount", successCount,
                "failureCount", results.size() - successCount, "items", results);
    }

    /** Accepts one conversation from an upstream system using a JSON contract. */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResourcePermission(code = "iqc:conversation:upload", name = "接口接入会话", type = "iqc", description = "通过接口传入单条会话")
    @Audit(operationType = OperationType.CREATE, description = "接口接入 IQC 会话", module = "IQC_CONVERSATION")
    @RateLimit(sceneCode = "iqc-conversation-ingest", maxCount = 120, period = 60)
    public Map<String, Object> ingest(@RequestBody ConversationIngestRequest request) {
        return ingestOne(request, null);
    }

    /** Accepts several complete conversations while isolating validation failures per item. */
    @PostMapping(value = "/ingest-batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResourcePermission(code = "iqc:conversation:upload", name = "批量接口接入会话", type = "iqc", description = "通过接口批量传入会话")
    @Audit(operationType = OperationType.CREATE, description = "批量接口接入 IQC 会话", module = "IQC_CONVERSATION")
    @RateLimit(sceneCode = "iqc-conversation-ingest-batch", maxCount = 30, period = 60)
    public Map<String, Object> ingestBatch(@RequestBody ConversationIngestBatchRequest request) {
        if (request == null || request.conversations() == null || request.conversations().isEmpty()) {
            throw IqcException.invalidArgument("conversations 不能为空");
        }
        if (request.conversations().size() > 100) throw IqcException.invalidArgument("单批最多接入 100 个会话");
        String batchNo = request.batchNo() == null || request.batchNo().isBlank()
                ? newBatchNo() : request.batchNo().trim();
        List<Map<String, Object>> items = new ArrayList<>();
        int successCount = 0;
        for (ConversationIngestRequest item : request.conversations()) {
            try {
                items.add(ingestOne(item, batchNo));
                successCount++;
            } catch (RuntimeException exception) {
                items.add(Map.of("status", "FAILED", "externalId", item == null || item.externalId() == null ? "" : item.externalId(),
                        "reason", exception.getMessage() == null ? "导入失败" : exception.getMessage()));
            }
        }
        return Map.of("batchNo", batchNo, "totalCount", request.conversations().size(),
                "successCount", successCount, "failureCount", request.conversations().size() - successCount,
                "items", items);
    }

    private Map<String, Object> ingestOne(ConversationIngestRequest request, String batchNoOverride) {
        ApiConversationInput input=request==null?null:new ApiConversationInput(request.externalId(),request.batchNo(),request.title(),request.metadata(),
                request.messages()==null?null:request.messages().stream().map(item->item==null?null:new ApiConversationInput.Message(item.time(),item.role(),item.content(),item.startMs(),item.endMs(),item.confidence())).toList());
        // Existing direct controller callers retain their constructor; managed calls share the injected service.
        var service=apiIngestService==null?new ConversationApiIngestService(importService,usageCounterRecorder):apiIngestService;
        var conversation=service.ingest(input,batchNoOverride);
        return Map.of("conversationId",conversation.getId(),"batchNo",conversation.getBatchNo()==null?"":conversation.getBatchNo(),
                "externalId",request.externalId()==null?"":request.externalId(),"messageCount",request.messages().size(),"status",conversation.getStatus());
    }

    private Map<String, Object> importFile(MultipartFile file, String batchNo, ConversationMetadata metadata) throws IOException {
        if (file.isEmpty()) {
            throw IqcException.invalidArgument("会话文件不能为空");
        }
        String fileName = file.getOriginalFilename() == null ? "conversation.txt" : file.getOriginalFilename();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw IqcException.invalidArgument("一期仅支持 .txt 会话文件");
        }
        long maxFileSizeBytes = uploadProperties.getMaxFileSizeBytes();
        if (maxFileSizeBytes <= 0 || file.getSize() > maxFileSizeBytes) {
            throw IqcException.invalidArgument("会话文件超过大小限制: " + maxFileSizeBytes + " bytes");
        }
        return importContent(fileName, file.getBytes(), batchNo, metadata);
    }

    private Map<String, Object> importContent(String fileName, byte[] content, String batchNo, ConversationMetadata metadata) {
        String objectId = fileFingerprint(content);
        String persistenceFingerprint = metadata == null ? objectId
                : fileFingerprint((objectId + '|' + metadata.fingerprintPart()).getBytes(StandardCharsets.UTF_8));
        usageCounterRecorder.record(new UsageRecord(
                "conversation-import:" + objectId + ":attempt",
                null,
                "iqc-platform",
                "CONVERSATION_FILE",
                objectId,
                "IMPORT",
                UsageOutcome.ATTEMPT));

        try {
            ConversationParseResult result = parser.parse(new String(content, StandardCharsets.UTF_8));
            var conversation = batchNo == null
                    ? importService.persist(fileName, persistenceFingerprint, result, null, "FILE", null, metadata)
                    : importService.persist(fileName, persistenceFingerprint, result, batchNo, "FILE", null, metadata);
            recordOutcome(objectId, result.successful() ? UsageOutcome.SUCCESS : UsageOutcome.FAILURE);

            return Map.of(
                    "fileName", fileName,
                    "batchNo", batchNo == null ? "" : batchNo,
                    "conversationId", conversation.getId(),
                    "messageCount", result.messages().size(),
                    "errorCount", result.errors().size(),
                    "ignoredBlankLines", result.ignoredBlankLines(),
                    "messages", result.messages(),
                    "errors", result.errors());
        } catch (RuntimeException exception) {
            // 文件读取、解析或持久化异常也必须落 FAILURE 计次，不能留下只有 ATTEMPT 的孤儿事件。
            recordOutcome(objectId, UsageOutcome.FAILURE);
            throw exception;
        }
    }

    private String newBatchNo() {
        return "CB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    public record ConversationIngestRequest(String externalId, String batchNo, String title,
                                            String employeeId, String employeeName, String employeeGroupId,
                                            String customerExternalId, String customerName, String customerContactMasked,
                                            String channel, java.time.LocalDateTime startedTime, java.time.LocalDateTime endedTime,
                                            String businessType, String businessNo, List<String> tags,
                                            List<IngestMessage> messages,
                                            String asrStatus, String customerLevel, Boolean complaint,
                                            Map<String, com.fasterxml.jackson.databind.JsonNode> businessFields, String employeeDepartmentId) {
        public ConversationIngestRequest(String externalId, String batchNo, String title,
                String employeeId, String employeeName, String employeeGroupId,
                String customerExternalId, String customerName, String customerContactMasked,
                String channel, java.time.LocalDateTime startedTime, java.time.LocalDateTime endedTime,
                String businessType, String businessNo, List<String> tags, List<IngestMessage> messages,
                String asrStatus, String customerLevel, Boolean complaint,
                Map<String, com.fasterxml.jackson.databind.JsonNode> businessFields) {
            this(externalId, batchNo, title, employeeId, employeeName, employeeGroupId, customerExternalId,
                    customerName, customerContactMasked, channel, startedTime, endedTime, businessType,
                    businessNo, tags, messages, asrStatus, customerLevel, complaint, businessFields, null);
        }
        public ConversationIngestRequest(String externalId, String batchNo, String title,
                String employeeId, String employeeName, String employeeGroupId,
                String customerExternalId, String customerName, String customerContactMasked,
                String channel, java.time.LocalDateTime startedTime, java.time.LocalDateTime endedTime,
                String businessType, String businessNo, List<String> tags, List<IngestMessage> messages) {
            this(externalId, batchNo, title, employeeId, employeeName, employeeGroupId, customerExternalId,
                    customerName, customerContactMasked, channel, startedTime, endedTime, businessType,
                    businessNo, tags, messages, null, null, null, null);
        }
        public ConversationIngestRequest(String externalId, String batchNo, String title, List<IngestMessage> messages) {
            this(externalId, batchNo, title, null, null, null, null, null, null, null,
                    null, null, null, null, List.of(), messages);
        }

        ConversationMetadata metadata() {
            return new ConversationMetadata(employeeId, employeeName, employeeGroupId, customerExternalId,
                    customerName, customerContactMasked, channel, startedTime, endedTime, businessType, businessNo, tags,
                    asrStatus, customerLevel, complaint, businessFields, employeeDepartmentId);
        }
    }

    public record ConversationIngestBatchRequest(String batchNo, List<ConversationIngestRequest> conversations) { }

    public record IngestMessage(String role,String time,String content,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long startMs,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long endMs,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.math.BigDecimal confidence) {
        public IngestMessage(String role,String time,String content){this(role,time,content,null,null,null);}
    }

    private void recordOutcome(String objectId, UsageOutcome outcome) {
        usageCounterRecorder.record(new UsageRecord(
                "conversation-import:" + objectId + (outcome == UsageOutcome.SUCCESS ? ":success" : ":failure"),
                null, "iqc-platform", "CONVERSATION_FILE", objectId, "IMPORT", outcome));
    }

    private String fileFingerprint(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
