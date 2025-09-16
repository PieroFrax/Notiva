package it.piero.notiva.model;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocUnitTextRequest {

    private String records;
    private List<String> fields;
    private String notes;

}
