package it.piero.notiva.service.implementation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.piero.notiva.model.BBox;
import it.piero.notiva.model.DocUnit;
import it.piero.notiva.model.Region;
import it.piero.notiva.service.definition.TextractService;
import it.piero.notiva.utils.PdfUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextractServiceImpl implements TextractService {

    private final TextractClient textractClient;
    private final PdfUtils pdfUtils;

    private static final double LINE_COVERAGE_THRESHOLD = 0.20;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Override
    public List<DocUnit> analyze(List<MultipartFile> files) throws IOException {
        log.info("avvio analisi documentale ({} file)", files.size());
        List<DocUnit> docUnits = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();

        for (MultipartFile fileItem : files) {

            List<byte[]> bytes = pdfUtils.renderPdfToImages(fileItem.getBytes(), 300);

            int pageNumber = 1;
            String origin = fileItem.getOriginalFilename();

            for (byte[] pageBytes : bytes) {


                Document document = Document.builder()
                        .bytes(SdkBytes.fromByteArray(pageBytes))
                        .build();

                AnalyzeDocumentRequest req = AnalyzeDocumentRequest.builder()
                        .document(document)
                        .featureTypes(FeatureType.TABLES, FeatureType.LAYOUT)
                        .build();

                AnalyzeDocumentResponse resp = textractClient.analyzeDocument(req);

                String jsonl = toJsonl(resp,pageNumber,origin);

                try (BufferedReader reader = new BufferedReader(new StringReader(jsonl))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        DocUnit docUnit = mapper.readValue(line, DocUnit.class);
                        docUnits.add(docUnit);
                    }
                }

                pageNumber++;
            }
        }

        return docUnits;
    }



    private String toJsonl(AnalyzeDocumentResponse response, int page, String origin) {
        StringBuilder out = new StringBuilder();

        Map<String, Block> byId = response.blocks().stream()
                .collect(Collectors.toMap(Block::id, Function.identity()));

        boolean hasLayout = hasLayout(response.blocks());

        Map<Integer, List<BoundingBox>> tableAreasByPage =
                hasLayout
                        ? buildLayoutBoxesByPage(response.blocks(), BlockType.LAYOUT_TABLE, page)
                        : buildCellBoxesByPage(response.blocks(), byId, page);

        List<DocUnit> units = new ArrayList<>();

        if (hasLayout) {
            Set<BlockType> freeTextTypes = Set.of(
                    BlockType.LAYOUT_TITLE,
                    BlockType.LAYOUT_SECTION_HEADER,
                    BlockType.LAYOUT_TEXT,
                    BlockType.LAYOUT_LIST,
                    BlockType.LAYOUT_HEADER,
                    BlockType.LAYOUT_FOOTER
            );

            response.blocks().stream()
                    .filter(b -> freeTextTypes.contains(b.blockType()))
                    .filter(b -> !isInsideAnyBox(b, tableAreasByPage, LINE_COVERAGE_THRESHOLD,page))
                    .forEach(b -> {
                        String text = getTextFromChildren(b, byId, true);
                        if (text != null) text = text.trim();
                        if (text != null && !text.isBlank()) {
                            BBox box = BBox.of(bboxOf(b));
                            Region region = classifyRegion(box);
                            units.add(DocUnit.textUnit(
                                    b.blockType().toString(),
                                    b.page() == null ? page : b.page(),
                                    box,
                                    fixSymbols(text),
                                    b.id(),
                                    b.confidence(),
                                    region,
                                    origin
                            ));
                        }
                    });
        } else {
            response.blocks().stream()
                    .filter(b -> b.blockType() == BlockType.LINE)
                    .filter(line -> !isInsideAnyBox(line, tableAreasByPage, LINE_COVERAGE_THRESHOLD,page))
                    .forEach(b -> {
                        String text = fixSymbols(b.text());
                        if (text != null && !text.isBlank()) {
                            BBox box = BBox.of(bboxOf(b));
                            Region region = classifyRegion(box);
                            units.add(DocUnit.textUnit(
                                    "LINE",
                                    b.page() == null ? page : b.page(),
                                    box,
                                    text.trim(),
                                    b.id(),
                                    b.confidence(),
                                    region,
                                    origin
                            ));
                        }
                    });
        }

        response.blocks().stream()
                .filter(b -> b.blockType() == BlockType.TABLE)
                .forEach(table -> {
                    List<Block> cells = getTableCells(table, byId);

                    NavigableMap<Integer, List<Block>> rowsMap = new TreeMap<>();
                    for (Block c : cells) {
                        int r = c.rowIndex() == null ? 1 : c.rowIndex();
                        rowsMap.computeIfAbsent(r, k -> new ArrayList<>()).add(c);
                    }
                    rowsMap.values().forEach(list ->
                            list.sort(Comparator.comparing(c -> c.columnIndex() == null ? 1 : c.columnIndex()))
                    );

                    List<List<String>> rows = new ArrayList<>();
                    for (List<Block> row : rowsMap.values()) {
                        int maxCol = row.stream()
                                .map(c -> c.columnIndex() == null ? 1 : c.columnIndex())
                                .max(Integer::compareTo)
                                .orElse(0);

                        List<String> cols = new ArrayList<>(Collections.nCopies(Math.max(0, maxCol), null));
                        for (Block c : row) {
                            int colIdx = (c.columnIndex() == null ? 1 : c.columnIndex()) - 1;
                            String val = getTextFromChildren(c, byId, true);
                            if (val != null) val = val.trim();
                            cols.set(colIdx, (val == null || val.isEmpty()) ? null : fixSymbols(val));
                        }
                        rows.add(cols);
                    }

                    BBox box = BBox.of(bboxOf(table));
                    Region region = classifyRegion(box);
                    units.add(DocUnit.tableUnit(
                            table.page() == null ? page : table.page(),
                            box,
                            rows,
                            table.id(),
                            table.confidence(),
                            region,
                            origin
                    ));
                });

        units.sort(Comparator
                .comparing(DocUnit::getPage, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(u -> safeTop(u.getBbox()), Comparator.nullsLast(Double::compareTo))
                .thenComparing(u -> u.getType().toString())
        );

        for (DocUnit u : units) {
            appendJsonLine(out, u);
        }

        return out.toString();
    }

    private static Region classifyRegion(BBox b) {
        if (b == null || b.getTop() == null) return Region.BODY;
        double t = b.getTop();
        if (t < 0.15) return Region.HEADER;
        if (t > 0.85) return Region.FOOTER;
        return Region.BODY;
    }

    private static Double safeTop(BBox b) {
        return (b == null || b.getTop() == null) ? 1.0 : b.getTop();
    }

    private static void appendJsonLine(StringBuilder out, DocUnit u) {
        try {
            out.append(MAPPER.writeValueAsString(u)).append("\n");
        } catch (JsonProcessingException e) {
            Map<String, Object> fallback = Map.of(
                    "type", u.getType() == null ? null : u.getType().toString(),
                    "error", e.getMessage()
            );
            try {
                out.append(MAPPER.writeValueAsString(fallback)).append("\n");
            } catch (JsonProcessingException ex) {
                out.append("{\"type\":\"ERROR\",\"error\":\"serialization failed\"}\n");
            }
        }
    }

    private boolean hasLayout(List<Block> blocks) {
        return blocks.stream().anyMatch(b ->
                b.blockType() == BlockType.LAYOUT_TEXT
                        || b.blockType() == BlockType.LAYOUT_TITLE
                        || b.blockType() == BlockType.LAYOUT_SECTION_HEADER
                        || b.blockType() == BlockType.LAYOUT_TABLE
                        || b.blockType() == BlockType.LAYOUT_LIST
                        || b.blockType() == BlockType.LAYOUT_HEADER
                        || b.blockType() == BlockType.LAYOUT_FOOTER
        );
    }

    private Map<Integer, List<BoundingBox>> buildLayoutBoxesByPage(List<Block> blocks, BlockType type,int pageNumber) {
        Map<Integer, List<BoundingBox>> byPage = new HashMap<>();
        blocks.stream()
                .filter(b -> b.blockType() == type)
                .forEach(b -> {
                    BoundingBox bb = bboxOf(b);
                    if (bb == null) return;
                    int page = b.page() == null ? pageNumber : b.page();
                    byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(bb);
                });
        return byPage;
    }

    private boolean isInsideAnyBox(Block block, Map<Integer, List<BoundingBox>> boxesByPage, double minCoverage, int pageNumber) {
        BoundingBox lb = bboxOf(block);
        if (lb == null) return false;
        int page = block.page() == null ? pageNumber : block.page();
        List<BoundingBox> boxes = boxesByPage.getOrDefault(page, Collections.emptyList());
        if (boxes.isEmpty()) return false;

        double la = area(lb);
        if (la <= 0) return false;

        for (BoundingBox cb : boxes) {
            double inter = intersectionArea(lb, cb);
            if (inter / la >= minCoverage) return true;
        }
        return false;
    }

    private String getTextFromChildren(Block parent, Map<String, Block> byId, boolean multiline) {
        if (parent == null || parent.relationships() == null) return "";
        List<String> lines = new ArrayList<>();

        for (Relationship rel : parent.relationships()) {
            if (rel.type() != RelationshipType.CHILD || rel.ids() == null) continue;

            List<Block> lineBlocks = rel.ids().stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(b -> b.blockType() == BlockType.LINE)
                    .collect(Collectors.toList());

            if (!lineBlocks.isEmpty()) {
                for (Block lb : lineBlocks) {
                    String t = fixSymbols(lb.text());
                    if (t != null && !t.isBlank()) lines.add(t);
                }
            } else {

                StringBuilder buf = new StringBuilder();
                for (String id : rel.ids()) {
                    Block b = byId.get(id);
                    if (b == null) continue;
                    switch (b.blockType()) {
                        case WORD -> {
                            String w = fixSymbols(b.text());
                            if (w != null) buf.append(w).append(' ');
                            if (w == null) buf.append(w).append(' ');
                        }
                        case SELECTION_ELEMENT -> {
                            String mark = b.selectionStatus() == SelectionStatus.SELECTED ? "☒" : "☐";
                            buf.append(mark).append(' ');
                        }
                        default -> {
                            String deep = getTextFromChildren(b, byId, multiline);
                            if (!deep.isBlank()) {
                                if (buf.length() > 0) buf.append(' ');
                                buf.append(deep);
                            }
                        }
                    }
                }
                String t = buf.toString().trim();
                if (!t.isBlank()) lines.add(t);
            }
        }

        if (lines.isEmpty()) {
            String t = fixSymbols(parent.text());
            return t == null ? "" : t.trim();
        }

        return (multiline ? String.join("\n", lines) : String.join(" ", lines)).trim();
    }

    private static double intersectionArea(BoundingBox a, BoundingBox b) {
        if (a == null || b == null) return 0;
        double ax1 = a.left(), ay1 = a.top();
        double ax2 = a.left() + a.width(), ay2 = a.top() + a.height();
        double bx1 = b.left(), by1 = b.top();
        double bx2 = b.left() + b.width(), by2 = b.top() + b.height();
        double ix = Math.max(0, Math.min(ax2, bx2) - Math.max(ax1, bx1));
        double iy = Math.max(0, Math.min(ay2, by2) - Math.max(ay1, by1));
        return ix * iy;
    }

    private String fixSymbols(String s) {
        if (s == null) return null;
        return s.replace('�', '€');
    }

    private Map<Integer, List<BoundingBox>> buildCellBoxesByPage(List<Block> blocks, Map<String, Block> byId, int pageNumber) {
        Map<Integer, List<BoundingBox>> byPage = new HashMap<>();
        blocks.stream()
                .filter(b -> b.blockType() == BlockType.TABLE)
                .forEach(table -> {
                    List<Block> cells = getTableCells(table, byId);
                    for (Block c : cells) {
                        BoundingBox bb = bboxOf(c);
                        if (bb == null) continue;
                        int page = c.page() != null ? c.page() : (table.page() == null ? pageNumber : table.page());
                        byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(bb);
                    }
                });
        return byPage;
    }

    private static List<Block> getTableCells(Block table, Map<String, Block> byId) {
        if (table.relationships() == null) return List.of();

        return table.relationships().stream()
                .filter(r -> r.type() == RelationshipType.CHILD && r.ids() != null)
                .flatMap(r -> r.ids().stream())
                .map(byId::get)
                .filter(Objects::nonNull)
                .flatMap(b -> {
                    if (b.blockType() == BlockType.CELL) return java.util.stream.Stream.of(b);
                    if (b.blockType() == BlockType.MERGED_CELL && b.relationships() != null) {
                        return b.relationships().stream()
                                .filter(r -> r.type() == RelationshipType.CHILD && r.ids() != null)
                                .flatMap(r -> r.ids().stream())
                                .map(byId::get)
                                .filter(bb -> bb != null && bb.blockType() == BlockType.CELL);
                    }
                    return java.util.stream.Stream.empty();
                })
                .collect(Collectors.toList());
    }

    private static BoundingBox bboxOf(Block b) {
        if (b == null || b.geometry() == null || b.geometry().boundingBox() == null) return null;
        return b.geometry().boundingBox();
    }

    private static double area(BoundingBox b) {
        if (b == null) return 0;
        return Math.max(0, b.width()) * Math.max(0, b.height());
    }
}
