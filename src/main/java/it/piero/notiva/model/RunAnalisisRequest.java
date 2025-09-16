package it.piero.notiva.model;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class RunAnalisisRequest {

    private List<String> fields;
    private String notes;
    private List<MultipartFile> files;

}
