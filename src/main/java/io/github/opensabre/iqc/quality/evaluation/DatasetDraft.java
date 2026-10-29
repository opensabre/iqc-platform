package io.github.opensabre.iqc.quality.evaluation;

import java.util.List;
import java.util.Objects;

/** Ordered membership always names exact reviewed versions. Dataset category is separate from candidate polarity. */
public record DatasetDraft(String schemaVersion, Type type, String description, List<Member> members) {
    public static final String SCHEMA = "iqc-dataset-draft-v1";
    public enum Type { BASIC, BOUNDARY, RISK, ADVERSARIAL, REGRESSION, CUSTOM }
    public record Member(String sampleId, int versionNo) {
        public Member {
            if (sampleId == null || sampleId.isBlank() || sampleId.length() > 64 || versionNo < 1)
                throw new IllegalArgumentException("invalid dataset member reference");
        }
    }
    public DatasetDraft {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported dataset draft schema");
        Objects.requireNonNull(type, "dataset type");
        if (description != null && description.length() > 2000) throw new IllegalArgumentException("dataset description too long");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty() || members.size() > 1000
                || members.stream().map(Member::sampleId).distinct().count() != members.size())
            throw new IllegalArgumentException("dataset requires 1–1000 unique samples");
    }
}
