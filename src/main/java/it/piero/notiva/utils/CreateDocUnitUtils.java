package it.piero.notiva.utils;

import it.piero.notiva.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateDocUnitUtils {

    public DocUnitRequest createDocUnitRequest(RunAnalisisRequest request, List<DocUnit> docUnits) {

        DocUnitRequest files = new DocUnitRequest();
        files.setFields(request.getFields());
        files.setRecords(docUnits);
        files.setNotes(request.getNotes());
        return files;

    }

    public DocUnitTextRequest createDocUnitRequestWithText(RunAnalisisRequest request, String docUnits) {

        DocUnitTextRequest files = new DocUnitTextRequest();
        files.setFields(request.getFields());
        files.setRecords(docUnits);
        files.setNotes(request.getNotes());
        return files;

    }

}
