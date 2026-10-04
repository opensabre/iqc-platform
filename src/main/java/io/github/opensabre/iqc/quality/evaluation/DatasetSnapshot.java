package io.github.opensabre.iqc.quality.evaluation;

import java.util.List;
import java.util.Objects;

/** Fully embedded reviewed inputs; execution must not load a live conversation by these IDs. */
public record DatasetSnapshot(String schemaVersion, String datasetId, String name, DatasetDraft.Type type,
                              String description, List<Member> members) {
    public static final String SCHEMA = "iqc-dataset-snapshot-v1";
    public record Member(String sampleId, int versionNo, int draftRevision, String contentHash, GoldConversation gold) {
        public Member {
            if (sampleId == null || sampleId.isBlank() || versionNo < 1 || draftRevision < 1
                    || contentHash == null || !contentHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid frozen dataset member identity");
            Objects.requireNonNull(gold, "gold");
            if (!sampleId.equals(gold.sourceSampleId())) throw new IllegalArgumentException("dataset member source mismatch");
        }
    }
    public DatasetSnapshot {
        if (!SCHEMA.equals(schemaVersion) || datasetId == null || datasetId.isBlank()
                || name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("invalid dataset snapshot identity");
        Objects.requireNonNull(type, "type");
        if (description != null && description.length() > 2000) throw new IllegalArgumentException("dataset description too long");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty() || members.size() > 1000
                || members.stream().map(Member::sampleId).distinct().count() != members.size()
                || members.stream().map(member -> member.gold().conversationId()).distinct().count() != members.size())
            throw new IllegalArgumentException("dataset requires unique samples and conversations");
        var types = new java.util.HashMap<String, io.github.opensabre.iqc.finding.Finding.ValueType>();
        for (Member member : members) for (GoldFinding finding : member.gold().findings()) {
            var previous = types.putIfAbsent(finding.code(), finding.valueType());
            if (previous != null && previous != finding.valueType())
                throw new IllegalArgumentException("dataset gold uses inconsistent types for " + finding.code());
        }
    }
}
