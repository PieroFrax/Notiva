package it.piero.notiva.model;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocUnitRequest {
    /**
     * Lista dei record estratti dal PDF (già ordinati per page/top e con region).
     */
    private List<DocUnit> records;

    /**
     * Nomi dei campi dinamici richiesti (es. ["societa_richiedente","oggetto","totale"]).
     */
    private List<String> fields;

    /**
     * Note opzionali (es. "i record sono ordinati dall'alto verso il basso").
     */
    private String notes;
}
