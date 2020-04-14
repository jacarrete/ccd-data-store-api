package uk.gov.hmcts.ccd.domain.service.getcasedocument;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CaseDocumentAttachOperationTest {

    @InjectMocks
    private CaseDocumentAttachOperation caseDocumentAttachOperation;

    HashMap<String, JsonNode> caseDetailsBefore;
    HashMap<String, JsonNode> caseDataContent;
    CaseDetails caseDetails;

    @BeforeEach
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);
        caseDetails = new CaseDetails();
         caseDetailsBefore = buildCaseData("case-detail-before-update.json");
        caseDataContent = buildCaseData("case-detail-after-update.json");
        caseDetails.setData(caseDetailsBefore);

    }


    @Test
    @DisplayName("should return document fields differences once updated ")
    void shouldReturnDeltaWhenDocumentFieldsUpdate() {
        Set<String> expectedOutput = new HashSet();
        expectedOutput.add("8da17150-c001-47d7-bfeb-3dabed9e0976");

        final Set<String> output = caseDocumentAttachOperation.differenceBeforeAndAfterInCaseDetails(caseDetails,caseDataContent);

        assertAll(
            () -> assertEquals(output, expectedOutput)

        );
    }
    @Test
    @DisplayName("should return document fields differences once document Fields inside Complex Field ")
    void shouldReturnDeltaWhenDocumentFieldsInsideComplexElement() throws IOException {
        HashMap<String, JsonNode> caseDataContent = buildCaseData("case-detail-after-with-complexFields-update.json");
        Set<String> expectedOutput = new HashSet();
        expectedOutput.add("8da17150-c001-47d7-bfeb-3dabed9e0976");

        final Set<String> output = caseDocumentAttachOperation.differenceBeforeAndAfterInCaseDetails(caseDetails,caseDataContent);

        assertAll(
            () -> assertEquals(output, expectedOutput)

        );
    }


    @Test
    @DisplayName("should return empty document set in case of CaseDataContent is empty")
    void shouldReturnEmptyDocumentSet() {
        caseDataContent=null;
        Set<String> expectedOutput = new HashSet();
        final Set<String> output = caseDocumentAttachOperation.differenceBeforeAndAfterInCaseDetails(caseDetails,caseDataContent);

        assertAll(
            () -> assertEquals(output, expectedOutput)

        );
    }

    static HashMap<String, JsonNode> buildCaseData(String fileName) throws IOException {
        InputStream inputStream =
            CaseDocumentAttachOperationTest.class.getClassLoader().getResourceAsStream("mappings/".concat(fileName));
        return
            new ObjectMapper().readValue(inputStream, new TypeReference<HashMap<String, JsonNode>>() {
            });
    }
}

