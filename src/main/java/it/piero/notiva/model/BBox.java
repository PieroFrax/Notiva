package it.piero.notiva.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BBox {
    private Float left;
    private Float top;
    private Float width;
    private Float height;

    public static BBox of(software.amazon.awssdk.services.textract.model.BoundingBox bb) {
        if (bb == null) return null;
        return BBox.builder()
                .left(bb.left())
                .top(bb.top())
                .width(bb.width())
                .height(bb.height())
                .build();
    }
}
