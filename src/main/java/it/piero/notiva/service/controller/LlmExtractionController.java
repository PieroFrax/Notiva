package it.piero.notiva.service.controller;

import it.piero.notiva.model.DocUnitRequest;
import it.piero.notiva.model.ExtractionResult;
import it.piero.notiva.service.implementation.FastExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/llm")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LlmExtractionController {

    private final FastExtractionService fastExtractionService;

    @PostMapping("extract")
    public ResponseEntity<ExtractionResult> analyze(@RequestBody DocUnitRequest request) {
        return ResponseEntity.ok(fastExtractionService.extract(request));
    }
}
