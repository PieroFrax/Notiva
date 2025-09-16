package it.piero.notiva.controller;

import it.piero.notiva.model.DocUnit;
import it.piero.notiva.service.definition.TextractService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("api/textract")
@CrossOrigin(origins = "*")
public class TextractController {

    private final TextractService textractService;

    public TextractController(TextractService textractService) {
        this.textractService = textractService;
    }

    @PostMapping("analyze")
    public ResponseEntity<List<DocUnit>> analyze(@RequestBody List<MultipartFile> files) throws Exception {
        return ResponseEntity.ok(textractService.analyze(files));
    }
}
