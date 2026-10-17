package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.quality.evaluation.GoldConversation;
import io.github.opensabre.iqc.quality.evaluation.GoldDraft;
import io.github.opensabre.iqc.quality.evaluation.GoldFinding;
import io.github.opensabre.iqc.quality.evaluation.GoldSampleService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.QualitySampleVersion;
import java.util.*;
import org.springframework.beans.BeanUtils;

/** Projects frozen gold for HTTP only. Evaluation and approval keep their original stored bytes. */
final class GoldSampleReadProjection {
    private final ObjectMapper json;
    GoldSampleReadProjection(ObjectMapper json) { this.json = Objects.requireNonNull(json); }

    QualitySample sample(QualitySample source, SensitiveReadAccessService.Access access, boolean goldEndpoint) {
        Objects.requireNonNull(access); if (source == null) return null;
        var copy = new QualitySample(); BeanUtils.copyProperties(source, copy);
        boolean candidateVisible = access.fullConversation() && access.pii();
        boolean canonical = candidateVisible && goldEndpoint;
        var withheld = new ArrayList<String>();
        if (!candidateVisible) {
            copy.setName(SensitiveTextProjection.mask(copy.getName()).text());
            copy.setContentSnapshot(null); copy.setExpectedJson(null); copy.setTagsJson(null);
            withheld.addAll(List.of("contentSnapshot", "expectedJson", "tagsJson"));
        }
        if (!canonical) { copy.setGoldDraftJson(null); withheld.add("goldDraftJson"); }
        var privacy = metadata(access, canonical, withheld);
        privacy.put("candidateContentVisible",candidateVisible);
        if (goldEndpoint && !canonical) preview(privacy, source.getGoldDraftJson(), access, source.getId(), source.getConversationId());
        else if (!goldEndpoint) privacy.put("previewState","GOLD_ENDPOINT_REQUIRED");
        copy.setPrivacy(privacy); return copy;
    }

    /** Writes acknowledge identity/revision only; a write grant must not substitute for a read grant. */
    QualitySample sampleMetadata(QualitySample source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access); if (source == null) return null;
        var copy = new QualitySample(); BeanUtils.copyProperties(source, copy);
        if (!(access.fullConversation() && access.pii())) copy.setName(SensitiveTextProjection.mask(copy.getName()).text());
        copy.setContentSnapshot(null); copy.setExpectedJson(null); copy.setTagsJson(null); copy.setGoldDraftJson(null);
        var privacy=metadata(access,false,List.of("contentSnapshot","expectedJson","tagsJson","goldDraftJson"));
        privacy.put("candidateContentVisible",false);privacy.put("previewState","RELOAD_REQUIRED");
        copy.setPrivacy(privacy);return copy;
    }

    QualitySampleVersion version(QualitySampleVersion source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access); if (source == null) return null;
        var copy = new QualitySampleVersion(); BeanUtils.copyProperties(source, copy);
        boolean canonical = access.fullConversation() && access.pii();
        var privacy = metadata(access, canonical, List.of("snapshotJson"));
        if (!canonical) {
            if(!access.fullConversation()) {
                copy.setReason(null);privacy.put("withheldFields",List.of("snapshotJson","reason"));
            } else copy.setReason(SensitiveTextProjection.mask(copy.getReason()).text());
            copy.setSnapshotJson(null); preview(privacy, source.getSnapshotJson(), access, source.getSampleId(), null);
        }
        copy.setPrivacy(privacy); return copy;
    }

    QualitySampleVersion versionMetadata(QualitySampleVersion source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access); if(source==null)return null;
        var copy=new QualitySampleVersion();BeanUtils.copyProperties(source,copy);
        var withheld=new ArrayList<String>(List.of("snapshotJson"));
        if(!access.fullConversation()){copy.setReason(null);withheld.add("reason");}
        else if(!access.pii())copy.setReason(SensitiveTextProjection.mask(copy.getReason()).text());
        copy.setSnapshotJson(null);
        var privacy=metadata(access,false,withheld);privacy.put("previewState","SUMMARY_ONLY");
        copy.setPrivacy(privacy);return copy;
    }

    private Map<String,Object> metadata(SensitiveReadAccessService.Access access, boolean canonical, List<String> withheld) {
        var result = new LinkedHashMap<String,Object>();
        result.put("policyVersion", "iqc-gold-read-v1"); result.put("canonicalPayloadVisible", canonical);
        result.put("canReadFull", access.fullConversation()); result.put("canReadPii", access.pii());
        result.put("withheldFields", canonical ? List.of() : withheld);
        return result;
    }

    private void preview(Map<String,Object> metadata, String snapshot, SensitiveReadAccessService.Access access,
            String expectedSampleId, String expectedConversationId) {
        if (snapshot == null || snapshot.isBlank()) { metadata.put("previewState", "NOT_INCLUDED"); return; }
        if (snapshot.length() > GoldSampleService.MAX_SNAPSHOT_BYTES) { metadata.put("previewState", "INVALID_GOLD"); return; }
        try {
            var schema = json.readTree(snapshot).path("schemaVersion").asText();
            List<GoldConversation.Message> messages; List<GoldFinding> findings; JsonNode context; String conversationId; String sourceSampleId;
            if (GoldDraft.SCHEMA.equals(schema)) {
                var draft = json.readValue(snapshot, GoldDraft.class);
                messages=draft.messages(); findings=draft.findings(); context=draft.businessContext(); conversationId=draft.conversationId(); sourceSampleId=draft.sourceSampleId();
            } else if (GoldConversation.SCHEMA.equals(schema)) {
                var gold = json.readValue(snapshot, GoldConversation.class);
                messages=gold.messages(); findings=gold.findings(); context=gold.businessContext(); conversationId=gold.conversationId(); sourceSampleId=gold.sourceSampleId();
            } else { metadata.put("previewState", "INVALID_GOLD"); return; }
            if(expectedSampleId==null || !expectedSampleId.equals(sourceSampleId)
                    || expectedConversationId!=null && !expectedConversationId.equals(conversationId)) {
                metadata.put("previewState","INVALID_GOLD");return;
            }
            var conversation = new Conversation(); conversation.setId(conversationId); conversation.setMessageCount(messages.size());
            if (context != null && context.path("conversation").isObject()) {
                var identities=context.path("conversation");
                conversation.setCustomerExternalId(identities.path("customerExternalId").asText(null));
                conversation.setEmployeeId(identities.path("employeeId").asText(null));
                conversation.setBusinessNo(identities.path("businessNo").asText(null));
            }
            var rows = messages.stream().map(message -> {
                var row = new ConversationMessage(); row.setId(message.id()); row.setConversationId(conversationId);
                row.setSequenceNo(message.sequence()); row.setSpeakerRole(message.role()); row.setContent(message.content());
                row.setRelativeTime(message.relativeTime()); row.setStartMs(message.startMs()); row.setEndMs(message.endMs()); row.setConfidence(message.confidence());
                return row;
            }).toList();
            // PII is never revealed in a projected gold preview; canonical data requires both grants.
            boolean full = access.fullConversation();
            var displayed=ConversationReadProjection.detail(Map.of("conversation",conversation,"messages",rows), access,
                    full ? ConversationReadProjection.Mode.FULL : ConversationReadProjection.Mode.PREVIEW);
            var preview=new LinkedHashMap<String,Object>(); preview.put("source", "FROZEN_GOLD");
            preview.put("messages",displayed.get("messages")); preview.put("privacy",displayed.get("privacy"));
            preview.put("findings",findings.stream().map(finding -> Map.of("code",finding.code(),"valueType",finding.valueType(),
                    "state",finding.state(),"evidenceMessageIds",finding.evidenceMessageIds())).toList());
            preview.put("findingValuesWithheld",true);
            metadata.put("preview",preview); metadata.put("previewState", "AVAILABLE");
        } catch (java.io.IOException | RuntimeException invalid) {
            metadata.put("previewState", "INVALID_GOLD");
        }
    }
}
