package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reuses exact published catalog versions and existing data scope checks. */
@Service @RequiredArgsConstructor
public class FindingLabelFreezeService {
    private final LabelResolutionService labels;
    private final ObjectMapper json;
    public FindingLabelPolicy freeze(FindingLabelPolicy requested) {
        var references=requested.bindings().stream().map(binding->new LabelResolutionService.LabelReference(
                binding.labelId(),binding.labelVersion())).distinct().toList();
        var directory=labels.resolveVersions(references);
        var frozen=new FindingLabelPolicy(requested.schemaVersion(),requested.bindings(),json.valueToTree(directory));
        frozen.validateFrozen();return frozen;
    }
}
