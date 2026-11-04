package com.shanzhu.beadhouse.service.common;

import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Wire protocol canonicalization, independent of application ObjectMapper customizations. */
public final class PolicyRagPayload {
    private static final ObjectMapper JSON=new ObjectMapper();
    static {
        // Python json.dumps uses lower-case hex for the remaining JSON control characters.
        JSON.getFactory().setCharacterEscapes(new CharacterEscapes() {
            private final int[] codes=CharacterEscapes.standardAsciiEscapesForJSON();
            { for(int i=0;i<32;i++) if(i!=8 && i!=9 && i!=10 && i!=12 && i!=13) codes[i]=ESCAPE_CUSTOM; }
            @Override public int[] getEscapeCodesForAscii() { return codes; }
            @Override public SerializableString getEscapeSequence(int c) {
                return c<32 ? new SerializedString(String.format(Locale.ROOT,"\\u%04x",c)) : null;
            }
        });
    }
    private PolicyRagPayload() { }
    public static String canonicalPayloadHash(JsonNode request) {
        try {
            SortedMap<String,Object> body=new TreeMap<>();
            for(String key:Arrays.asList("action","document_key","source","title")) body.put(key,text(request,key));
            if(!"UPSERT".equals(body.get("action")) && !"DELETE".equals(body.get("action"))) throw new IllegalArgumentException("同步操作不合法");
            if(!request.path("chunks").isArray()) throw new IllegalArgumentException("同步片段不能为空");
            List<JsonNode> ordered=new ArrayList<>(); request.path("chunks").forEach(ordered::add);
            ordered.sort(Comparator.comparingLong((JsonNode n)->integer(n,"section_no")).thenComparingLong(n->integer(n,"chunk_id")));
            List<Map<String,Object>> chunks=new ArrayList<>();
            for(JsonNode item:ordered) {
                SortedMap<String,Object> chunk=new TreeMap<>();
                chunk.put("chunk_id",integer(item,"chunk_id")); chunk.put("content",text(item,"content"));
                chunk.put("section_no",integer(item,"section_no")); chunks.add(chunk);
            }
            if(("DELETE".equals(body.get("action")) && !chunks.isEmpty()) || ("UPSERT".equals(body.get("action")) && chunks.isEmpty()))
                throw new IllegalArgumentException("同步操作与片段不一致");
            body.put("chunks",chunks);
            return sha256(JSON.writeValueAsString(body));
        } catch(IllegalArgumentException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("同步摘要不可用",failure); }
    }
    public static String snapshot(String key,long version,String action,String title,String source,List<PolicyDoc> docs) {
        try {
            ObjectNode request=JSON.createObjectNode();
            request.put("document_key",key); request.put("version",version); request.put("action",action);
            request.put("title",title); request.put("source",source);
            List<PolicyDoc> ordered=new ArrayList<>(docs);
            ordered.sort(Comparator.comparing(PolicyDoc::getSectionNo).thenComparing(PolicyDoc::getId));
            com.fasterxml.jackson.databind.node.ArrayNode chunks=request.putArray("chunks");
            for(PolicyDoc doc:ordered) {
                ObjectNode chunk=chunks.addObject(); chunk.put("chunk_id",doc.getId());
                chunk.put("section_no",doc.getSectionNo()); chunk.put("content",doc.getContent());
            }
            request.put("payload_hash",canonicalPayloadHash(request));
            return JSON.writeValueAsString(request);
        } catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("同步快照不可用",failure); }
    }
    public static JsonNode parse(String value) {
        try { return JSON.readTree(value); }
        catch(Exception failure) { throw new IllegalArgumentException("同步快照不合法",failure); }
    }
    public static String sha256(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(); for(byte b:digest) out.append(String.format(Locale.ROOT,"%02x",b&255));
            return out.toString();
        } catch(Exception failure) { throw new IllegalStateException("SHA-256 unavailable",failure); }
    }
    private static long integer(JsonNode parent,String field) {
        JsonNode value=parent.path(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("同步标识不合法");
        return value.longValue();
    }
    private static String text(JsonNode parent,String field) {
        JsonNode value=parent.path(field);
        if(!value.isTextual()) throw new IllegalArgumentException("同步文本字段不合法");
        String result=value.textValue();
        for(int i=0;i<result.length();i++) {
            char c=result.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=result.length() || !Character.isLowSurrogate(result.charAt(i))) throw new IllegalArgumentException("同步文本编码不合法");
            } else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException("同步文本编码不合法");
        }
        return result;
    }
}
