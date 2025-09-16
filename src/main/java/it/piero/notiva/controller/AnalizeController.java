package it.piero.notiva.controller;

import it.piero.notiva.model.DocUnit;
import it.piero.notiva.model.DocUnitRequest;
import it.piero.notiva.model.ExtractionResult;
import it.piero.notiva.model.RunAnalisisRequest;
import it.piero.notiva.service.definition.TextractService;
import it.piero.notiva.service.implementation.FastExtractionService;
import it.piero.notiva.utils.CreateDocUnitUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/analyze")
@CrossOrigin(origins = "*")
public class AnalizeController {

    private final TextractService textractService;
    private final FastExtractionService fastExtractionService;
    private final CreateDocUnitUtils createDocUnitUtils;

    public AnalizeController(TextractService textractService, FastExtractionService fastExtractionService, CreateDocUnitUtils createDocUnitUtils) {
        this.textractService = textractService;
        this.fastExtractionService = fastExtractionService;
        this.createDocUnitUtils = createDocUnitUtils;
    }

    @PostMapping("/run")
    public ResponseEntity<ExtractionResult> analyze(@RequestBody RunAnalisisRequest request) throws Exception {

        List<DocUnit> docUnits = textractService.analyze(request.getFiles());
        DocUnitRequest docUnitRequest = createDocUnitUtils.createDocUnitRequest(request, docUnits);
        return ResponseEntity.ok(fastExtractionService.extract(docUnitRequest));
    }

    @PostMapping(
            value = "/run-test",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ExtractionResult> analyzeTest(@ModelAttribute RunAnalisisRequest request) throws Exception {
        List<DocUnit> docUnits = textractService.analyze(request.getFiles());
        DocUnitRequest docUnitRequest = createDocUnitUtils.createDocUnitRequest(request, docUnits);
        return ResponseEntity.ok(fastExtractionService.extract(docUnitRequest));
    }
}
