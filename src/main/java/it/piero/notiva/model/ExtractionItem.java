package it.piero.notiva.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtractionItem {
    private String name;
    private String value;
    private Evidence evidence;
    private String status;
    private Double confidence;
    private List<String> alternatives;
}
