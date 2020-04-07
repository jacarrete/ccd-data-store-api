package uk.gov.hmcts.ccd.domain.types.sanitiser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseField;
import uk.gov.hmcts.ccd.domain.model.definition.CaseType;
import uk.gov.hmcts.ccd.domain.model.definition.FieldType;
import uk.gov.hmcts.ccd.domain.model.search.DocumentMetadata;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocument;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Named
@Singleton
public class CaseSanitiser {

    public static final String COMPLEX = "Complex";
    public static final String COLLECTION = "Collection";
    public static final String DOCUMENT = "Document";
    public static final String DOCUMENT_CASE_FIELD_URL_ATTRIBUTE = "document_url";
    public static final String DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE = "document_binary_url";
    public static final String BAD_REQUEST_EXCEPTION_DOCUMENT_INVALID = "DocumentId is not valid";
    public static final String HASH_CODE_STRING = "hashcode";
    public static final String CONTENT_TYPE = "content-type";

    private final Map<String, Sanitiser> sanitisers = new HashMap<>();

    @Inject
    public CaseSanitiser(List<Sanitiser> sanitisers) {
        sanitisers.forEach(sanitiser -> {
            this.sanitisers.put(sanitiser.getType(), sanitiser);
        });
    }

    public Map<String, JsonNode> sanitise(final CaseType caseType, final Map<String, JsonNode> caseData) {

        final Map<String, JsonNode> sanitisedData = new HashMap<>();

        if (null == caseData) {
            return sanitisedData;
        }

        final Map<String, CaseField> fieldsMap = new HashMap<>();

        caseType.getCaseFields().forEach(field -> {
            fieldsMap.put(field.getId(), field);
        });

        caseData.forEach((key, value) -> {
            if (fieldsMap.containsKey(key)) {
                final CaseField caseField = fieldsMap.get(key);
                final FieldType fieldType = caseField.getFieldType();

                if (sanitisers.containsKey(fieldType.getType())) {
                    final Sanitiser sanitiser = sanitisers.get(fieldType.getType());
                    sanitisedData.put(key, sanitiser.sanitise(fieldType, value));
                } else {
                    sanitisedData.put(key, value);
                }
            }
        });

        return sanitisedData;
    }

    public Set<String> sanitizeForAttachedDocsToCase(final CaseDetails caseDetails, final Map<String, JsonNode> caseData) {

        final Map<String, JsonNode> sanitisedDataForAttachDocs = new HashMap<>();
        final Set<String> filterDocumentSet = new HashSet<>();

        if (null == caseData) {
            return filterDocumentSet;
        }

        caseData.forEach((key, value) -> {

            if ((value.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null) && caseDetails.getData().containsKey(key)) {
                    if(!value.equals(caseDetails.getData().get(key)))
                    {
                        sanitisedDataForAttachDocs.put(key,value);
                    }
            } else if (value.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null){
                sanitisedDataForAttachDocs.put(key,value);
            }
        });
        selectDocument(sanitisedDataForAttachDocs,filterDocumentSet);
        return filterDocumentSet;
    }

    public void extractDocumentFields(DocumentMetadata documentMetadata, Map<String, JsonNode> data,Set<String> updatedDocumentSet) {
        data.forEach((field, jsonNodeValue) -> {
            //Check if the field consists of Document at any level, e.g. Complex fields can also have documents.
            //This quick check will reduce the processing time as most of filtering will be done at top level.
            if (jsonNodeValue != null && jsonNodeValue.get(HASH_CODE_STRING) != null) {
                //Check if current node is of type document and hashcode is available.
                JsonNode documentBinaryField = jsonNodeValue.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE);
                JsonNode documentField = jsonNodeValue.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE);
                if ((documentField != null || documentBinaryField != null) && jsonNodeValue.get(HASH_CODE_STRING) != null
                    ) {

                    String documentId="";
                   if(documentBinaryField != null) {
                       documentId = documentBinaryField.asText().substring(documentBinaryField.asText().length() - 43, documentBinaryField.asText().length() - 7);
                       documentMetadata.getDocuments().add(CaseDocument
                           .builder()
                           .id(documentId)
                           .hashToken(jsonNodeValue.get(HASH_CODE_STRING).asText())
                           .build());
                   }else {
                       documentId = documentField.asText().substring(documentField.asText().length() - 36);
                       documentMetadata.getDocuments().add(CaseDocument
                           .builder()
                           .id(documentId)
                           .hashToken(jsonNodeValue.get(HASH_CODE_STRING).asText())
                           .build());
                   }
                    if (jsonNodeValue instanceof ObjectNode) {
                        ((ObjectNode) jsonNodeValue).remove(HASH_CODE_STRING);
                    }
                     updatedDocumentSet.add(documentId);

                } else {
                    jsonNodeValue.fields().forEachRemaining(node -> extractDocumentFields(documentMetadata, (Map<String, JsonNode>) node,updatedDocumentSet));
                }
            }
        });
    }

    private void selectDocument(Map<String, JsonNode> sanitisedDataToAttachDoc, Set<String> filterDocumentSet) {

        sanitisedDataToAttachDoc.forEach((field, jsonNodeValue) -> {
            if(jsonNodeValue !=null && (jsonNodeValue.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE )!= null || jsonNodeValue.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null)){

                JsonNode documentBinaryField = jsonNodeValue.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE);
                if (documentBinaryField != null) {
                    filterDocumentSet.add(documentBinaryField.asText().substring(documentBinaryField.asText().length() - 43, documentBinaryField.asText().length() - 7));

                } else {
                    JsonNode documentField = jsonNodeValue.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE);
                    filterDocumentSet.add(documentField.asText().substring(documentField.asText().length() - 36));
                }
            }

        });


    }

}
