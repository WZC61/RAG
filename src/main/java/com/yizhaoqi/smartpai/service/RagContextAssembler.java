package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.RetrievalResponse;
import com.yizhaoqi.smartpai.entity.RetrievalResult;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One bounded evidence registry per answer. No model calls, storage access or ranking algorithm. */
@Component
public class RagContextAssembler {
    private final int maxChars;
    private final int maxEvidenceChars;

    public RagContextAssembler() {
        this(12000, 1800);
    }

    @Autowired
    public RagContextAssembler(@Value("${rag.context.max-chars:12000}") int maxChars,
                               @Value("${rag.context.max-evidence-chars:1800}") int maxEvidenceChars) {
        if (maxChars < 512 || maxEvidenceChars < 128) {
            throw new IllegalArgumentException("Invalid RAG context budget");
        }
        this.maxChars = maxChars;
        this.maxEvidenceChars = Math.min(maxEvidenceChars, maxChars - 256);
    }

    public Session newSession() {
        return new Session();
    }

    public record Evidence(int number, String identity, RetrievalResult source, String text,
                           String query, String retrievalMode, boolean degraded) {}
    public record Context(String text, List<Evidence> evidence, boolean degraded, boolean empty) {}

    public final class Session {
        private final Map<String, Evidence> registry = new LinkedHashMap<>();
        private int usedChars;
        private boolean degraded;
        private boolean budgetLimited;
        public synchronized Context add(RetrievalResponse response, String query) {
            degraded |= response.isDegraded();
            List<Evidence> selected = new ArrayList<>();
            // RetrievalResult is already in RRF order: never rerank it here.
            for (RetrievalResult result : response.results()) {
                String identity = identity(result);
                Evidence existing = registry.get(identity);
                if (existing != null) {
                    if (!selected.contains(existing)) selected.add(existing);
                    continue;
                }
                String semantic = semanticText(result);
                if (semantic.isBlank()) continue;
                int number = registry.size() + 1;
                String header = "[" + number + "] " + result.getDocumentType() + " | " + headerLabel(result.getFileName(), 80)
                        + (result.getPageNumber() == null ? "" : " | 第" + result.getPageNumber() + "页")
                        + (result.getDocumentType() == EsDocument.DocumentType.FIGURE
                                ? " | " + headerLabel(result.getFigureLabel(), 60) : "") + "\n";
                int available = Math.min(maxEvidenceChars, maxChars - 256 - usedChars);
                if (available - header.length() - 2 < 32) {
                    budgetLimited = true;
                    continue;
                }
                String body = header + limit(semantic, available - header.length() - 2) + "\n\n";
                Evidence evidence = new Evidence(number, identity, result, body, query,
                        response.getRetrievalMode(), response.isDegraded());
                registry.put(identity, evidence);
                usedChars += body.length();
                selected.add(evidence);
            }
            return context(selected, response.isDegraded(), response.results().isEmpty());
        }

        public synchronized Context snapshot() {
            return context(new ArrayList<>(registry.values()), degraded, registry.isEmpty());
        }

        private Context context(List<Evidence> evidence, boolean degraded, boolean empty) {
            String status = degraded ? "检索降级：仅部分通道可用，证据可能不完整。\n" : "";
            if (evidence.isEmpty()) {
                status += empty ? (degraded ? "本次" : "正常") + "检索未获得可用证据；现有资料不足以确定答案。\n"
                        : "本次没有可加入上下文的新证据；可能因预算不足或片段为空。\n";
            }
            if (budgetLimited) status += "证据已按总预算截取。\n";
            return new Context(status + evidence.stream().map(Evidence::text).collect(Collectors.joining()),
                    List.copyOf(evidence), degraded, empty);
        }
    }
    private String identity(RetrievalResult result) {
        if (result.getEntryId()!=null&&!result.getEntryId().isBlank()) return result.getEntryId();
        // Only legacy list callers lack entryId/generation; keep that identity explicitly separate.
        return "LEGACY:"+result.getDocumentType()+":"+result.getFileMd5()+":"+result.getProcessingGeneration()+":"+result.getPageNumber()+":"+result.getChunkId()+":"+result.getFigureIndex();
    }
    private String semanticText(RetrievalResult r) {
        if (r.getDocumentType()!=EsDocument.DocumentType.FIGURE) return Objects.toString(r.getTextContent(),"").trim();
        List<String> fields = new ArrayList<>();
        long populated = Stream.of(r.getCaption(), r.getDescription(), r.getOcrText())
                .filter(value -> value != null && !value.isBlank()).count();
        int cap = Math.max(32, (maxEvidenceChars - 200) / (int) Math.max(1, populated));
        if (r.getCaption()!=null&&!r.getCaption().isBlank()) fields.add("Caption: "+limit(r.getCaption(),cap));
        if (r.getDescription()!=null&&!r.getDescription().isBlank()) fields.add("Description: "+limit(r.getDescription(),cap));
        if (r.getOcrText()!=null&&!r.getOcrText().isBlank()) fields.add("OCR: "+limit(r.getOcrText(),cap));
        return String.join("\n",fields);
    }
    private static String headerLabel(String value, int max) {
        return limit(value, max).replaceAll("[\\r\\n]", " ");
    }
    private static String limit(String value,int max) {
        String text=Objects.toString(value,"").trim();
        return text.length()<=max?text:text.substring(0,Math.max(0,max-1))+"…";
    }
    /** Compatibility only; the primary path consumes RetrievalResult directly. */
    public Context legacyContext(List<SearchResult> results) {
        List<RetrievalResult> values=new ArrayList<>();
        for (SearchResult old:results) {
            RetrievalResult r=new RetrievalResult(); r.setEntryId(old.getEntryId());r.setFileMd5(old.getFileMd5());
            r.setDocumentType(old.getDocumentType()==null?EsDocument.DocumentType.TEXT:old.getDocumentType());
            r.setProcessingGeneration(old.getProcessingGeneration());r.setFileName(old.getFileName());r.setPageNumber(old.getPageNumber());
            r.setChunkId(old.getChunkId());r.setAnchorText(old.getAnchorText());r.setTextContent(old.getTextContent());
            r.setFigureIndex(old.getFigureIndex());r.setFigureLabel(old.getFigureLabel());r.setCaption(old.getCaption());
            r.setDescription(old.getDescription());r.setOcrText(old.getOcrText());r.setImagePath(old.getImagePath());values.add(r);
        }
        return newSession().add(new RetrievalResponse(values,Set.of(),values.size(),values.size()),"");
    }
}
