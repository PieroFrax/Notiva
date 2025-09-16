package it.piero.notiva.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocUnit {

    private DocUnitType type;
    private String subtype;
    private Integer page;
    private BBox bbox;
    private String blockId;
    private String text;
    private List<List<String>> rows;
    private Float confidence;
    private Region region;
    private String origin;

    public static DocUnit textUnit(String subtype, Integer page, BBox bbox,
                                   String text, String blockId, Float confidence, Region region, String origin) {
        return DocUnit.builder()
                .type(DocUnitType.TEXT)
                .subtype(subtype)
                .page(page)
                .bbox(bbox)
                .text(text)
                .blockId(blockId)
                .confidence(confidence)
                .region(region)
                .origin(origin)
                .build();
    }

    public static DocUnit tableUnit(Integer page, BBox bbox, List<List<String>> rows,
                                    String blockId, Float confidence, Region region, String origin) {
        return DocUnit.builder()
                .type(DocUnitType.TABLE)
                .page(page)
                .bbox(bbox)
                .rows(rows)
                .blockId(blockId)
                .confidence(confidence)
                .region(region)
                .origin(origin)
                .build();
    }
}
