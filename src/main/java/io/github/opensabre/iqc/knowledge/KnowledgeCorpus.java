package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** Immutable reference material, distinct from instructions, conversations and evaluation labels. */
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=KnowledgeCorpus.Codec.class)
public record KnowledgeCorpus(String schemaVersion,List<Document> documents) {
    public static final String SCHEMA="iqc-knowledge-corpus-v1";
    public record Document(String documentId,String title,String text,String sourceReference){
        public Document {
            if(documentId==null||!documentId.matches("[A-Za-z0-9:_-]{1,64}")||title==null||title.isBlank()||title.length()>200
                    ||text==null||text.isBlank()||text.length()>16000||sourceReference==null||sourceReference.isBlank()
                    ||sourceReference.length()>512||!sourceReference.equals(sourceReference.strip())
                    ||sourceReference.codePoints().anyMatch(Character::isISOControl)||!unicode(title)||!unicode(text)||!unicode(sourceReference))throw new IllegalArgumentException("invalid knowledge document");
        }
    }
    public KnowledgeCorpus {
        if(!SCHEMA.equals(schemaVersion)||documents==null||documents.isEmpty()||documents.size()>100||documents.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("knowledge corpus requires 1–100 documents");
        if(documents.stream().map(Document::documentId).distinct().count()!=documents.size()
                ||documents.stream().mapToLong(d->d.text().length()).sum()>200000)throw new IllegalArgumentException("duplicate knowledge document or corpus too large");
        documents=documents.stream().sorted(java.util.Comparator.comparing(Document::documentId)).toList();
    }
    private static boolean unicode(String value){return value.codePoints().noneMatch(point->point>=0xD800&&point<=0xDFFF);}
    private static void fields(JsonNode node,Set<String> names){
        if(node==null||!node.isObject())throw new IllegalArgumentException("knowledge object required");
        node.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new IllegalArgumentException("unknown knowledge field");});
    }
    private static String text(JsonNode node,String name){
        var value=node.get(name);if(value==null||!value.isTextual())throw new IllegalArgumentException("knowledge string required");return value.textValue();
    }
    public static KnowledgeCorpus fromTree(JsonNode node){
        fields(node,Set.of("schemaVersion","documents"));var documents=node.path("documents");
        if(!documents.isArray()||documents.size()>100)throw new IllegalArgumentException("knowledge documents array required");
        var values=new java.util.ArrayList<Document>();
        for(var item:documents){fields(item,Set.of("documentId","title","text","sourceReference"));
            values.add(new Document(text(item,"documentId"),text(item,"title"),text(item,"text"),text(item,"sourceReference")));}
        return new KnowledgeCorpus(text(node,"schemaVersion"),values);
    }
    public static final class Codec extends com.fasterxml.jackson.databind.JsonDeserializer<KnowledgeCorpus>{
        @Override public KnowledgeCorpus deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException{
            JsonNode node=parser.getCodec().readTree(parser);
            try{return fromTree(node);}catch(IllegalArgumentException invalid){return context.reportInputMismatch(KnowledgeCorpus.class,"知识库须为有界、唯一ID的纯文本参考文档集合");}
        }
    }
}
