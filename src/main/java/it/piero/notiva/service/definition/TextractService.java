package it.piero.notiva.service.definition;

import it.piero.notiva.model.DocUnit;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;

import java.io.IOException;
import java.util.List;

public interface TextractService {

    List<DocUnit> analyze(List<MultipartFile> file) throws IOException;

}
