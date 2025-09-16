package it.piero.notiva.controller;

import it.piero.notiva.model.DocUnitRequest;
import it.piero.notiva.model.ExtractionResult;
import it.piero.notiva.service.implementation.FastExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/llm")
@CrossOrigin(origins = "*")
public class LlmExtractionController {

    private final FastExtractionService fastExtractionService;

    public LlmExtractionController(FastExtractionService fastExtractionService) {
        this.fastExtractionService = fastExtractionService;
    }

    @PostMapping("extract")
    public ResponseEntity<ExtractionResult> analyze(@RequestBody DocUnitRequest request) {
        return ResponseEntity.ok(fastExtractionService.extract(request));
    }
}
