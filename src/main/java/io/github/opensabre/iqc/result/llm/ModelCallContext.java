package io.github.opensabre.iqc.result.llm;

/** Explicit immutable execution provenance; never inherited from executor thread locals. */
public record ModelCallContext(String taskId,String executionId,String nodeId,int runIndex,
                               String owner,String ownerGroupId,String conversationId) {
    public ModelCallContext {
        id(taskId,64,false);id(executionId,64,false);id(nodeId,256,true);id(ownerGroupId,64,true);id(conversationId,64,false);
        if(runIndex<0 || owner==null || owner.isBlank() || owner.length()>128 || owner.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid model execution provenance");
    }
    private static void id(String value,int maximum,boolean nullable){
        if(value==null && nullable)return;
        if(value==null || value.length()>maximum || !value.matches("[A-Za-z0-9:_-]+"))throw new IllegalArgumentException("invalid model execution identity");
    }
}
