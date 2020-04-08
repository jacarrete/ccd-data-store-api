package uk.gov.hmcts.ccd.domain.service.getcasedocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.ccd.ApplicationParams;
import uk.gov.hmcts.ccd.data.SecurityUtils;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.search.DocumentMetadata;
import uk.gov.hmcts.ccd.endpoint.exceptions.CaseConcurrencyException;
import uk.gov.hmcts.ccd.endpoint.exceptions.DataParsingException;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CaseDocumentAttachOperation {

    private static final Logger LOG = LoggerFactory.getLogger(CaseDocumentAttachOperation.class);

    Set<String> documentSetBeforeCallback = null;
    Set<String> documentAfterCallback = null;
    DocumentMetadata documentMetadata = null;
    public static final String CASE_DATA_PARSING_EXCEPTION = "Exception while extracting the document fields from Case payload";
    public static final String COMPLEX = "Complex";
    public static final String COLLECTION = "Collection";
    public static final String DOCUMENT = "Document";
    public static final String DOCUMENT_CASE_FIELD_URL_ATTRIBUTE = "document_url";
    public static final String DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE = "document_binary_url";
    public static final String HASH_CODE_STRING = "hashcode";
    private final RestTemplate restTemplate;
    private final ApplicationParams applicationParams;
    private final SecurityUtils securityUtils;
    public static final String DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION = "The documents have been altered outside the create case transaction";

    public CaseDocumentAttachOperation(@Qualifier("restTemplate") final RestTemplate restTemplate,
                                       ApplicationParams applicationParams,
                                       SecurityUtils securityUtils) {
        this.restTemplate = restTemplate;
        this.applicationParams = applicationParams;
        this.securityUtils = securityUtils;
    }

    public void  beforeCallbackPrepareDocumentMetaData(CaseDetails caseDetails){
        try {
            LOG.debug("Updating  case using Version 2.1 of case create API");
            documentSetBeforeCallback = new HashSet<>();
            documentMetadata = DocumentMetadata.builder()
                .caseId(caseDetails.getReferenceAsString())
                .jurisdictionId(caseDetails.getJurisdiction())
                .caseTypeId(caseDetails.getCaseTypeId())
                .documents(new ArrayList<>())
                .build();

            extractDocumentFields(documentMetadata, caseDetails.getData(), documentSetBeforeCallback);
        }
        catch (Exception e) {
            LOG.error(CASE_DATA_PARSING_EXCEPTION);
            throw new DataParsingException(CASE_DATA_PARSING_EXCEPTION);
        }
    }

    public void  afterCallbackPrepareDocumentMetaData(CaseDetails caseDetails){
        try {
            documentAfterCallback = new HashSet<>();
            // to remove hashcode before compute delta
            extractDocumentFields(documentMetadata, caseDetails.getData(),documentAfterCallback);
        }
        catch (Exception e) {
            LOG.error(CASE_DATA_PARSING_EXCEPTION);
            throw new DataParsingException(CASE_DATA_PARSING_EXCEPTION);
        }
    }
    public void filterDocumentFields(){
        filterDocumentFields(documentMetadata, documentSetBeforeCallback, documentAfterCallback);
    }
    public void restCallToAttachCaseDocuments(){
        HttpEntity<DocumentMetadata> requestEntity = new HttpEntity<>(documentMetadata, securityUtils.authorizationHeaders());
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory());

        try {
            if (!documentMetadata.getDocuments().isEmpty()) {
                ResponseEntity<Boolean> result = restTemplate
                    .exchange(applicationParams.getCaseDocumentAmApiHost().concat(applicationParams.getAttachDocumentPath()),
                        HttpMethod.PATCH, requestEntity, Boolean.class);

                if (!result.getStatusCode().equals(HttpStatus.OK) || result.getBody() == null || result.getBody().equals(false)) {
                    LOG.error(DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION);
                    throw new CaseConcurrencyException(DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION);
                }
            }
        } catch (Exception e) {
            LOG.error(DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION);
            throw new CaseConcurrencyException(DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION);
        }
    }


    private void extractDocumentFields(DocumentMetadata documentMetadata, Map<String, JsonNode> data, Set<String> updatedDocumentSet) {
        data.forEach((field, jsonNodeValue) -> {
            //Check if the field consists of Document at any level, e.g. Complex fields can also have documents.
            //This quick check will reduce the processing time as most of filtering will be done at top level.
            if (jsonNodeValue != null && jsonNodeValue.findValue (HASH_CODE_STRING) != null) {
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

    private void filterDocumentFields(DocumentMetadata documentMetadata, Set<String> documentSetBeforeCallback, Set<String> documentSetAfterCallback) {
        try {

           //The below line is STRICTLY for LOCAL testing purpose. It needs to be removed in the PR environment
            documentSetAfterCallback.addAll(documentSetBeforeCallback);


            //find documents which are intersection of Before and after callback
            Set<String> filteredDocumentSet = documentSetAfterCallback.stream()
                .filter(documentSetBeforeCallback::contains)
                .collect(Collectors.toSet());

            //Add the intersection to aftercallback list. Now, afterCallbackList will have the documents from
            //Callback response
            // + original documents which have not been removed by the callback
            // + Any new documents which are added by callback response
            //This code should drop any documents which were removed by the callback
            documentSetAfterCallback.addAll(filteredDocumentSet);

            //The following code will filter the documents based on above prepared Set.
            List<CaseDocument> caseDocumentList = documentMetadata.getDocuments()
                .stream()
                .filter(document -> documentSetAfterCallback.contains(document.getId()))
                .collect(Collectors.toList());
            documentMetadata.setDocuments(caseDocumentList);
        } catch (Exception e) {
            LOG.error("Exception while filtering the document fields.");
            throw new DataParsingException("Exception while filtering the document fields.");
        }

    }
    public Set<String> differenceBeforeAndAfterInCaseDetails(final CaseDetails caseDetails, final Map<String, JsonNode> caseData) {

        final Map<String, JsonNode> documentsDifference = new HashMap<>();
        final Set<String> filterDocumentSet = new HashSet<>();

        if (null == caseData) {
            return filterDocumentSet;
        }

        caseData.forEach((key, value) -> {

            if ((value.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null) && caseDetails.getData().containsKey(key)) {
                if(!value.equals(caseDetails.getData().get(key)))
                {
                    documentsDifference.put(key,value);
                }
            } else if (value.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null){
                documentsDifference.put(key,value);
            }
        });
        selectDocument(documentsDifference,filterDocumentSet);
        return filterDocumentSet;
    }
    private void selectDocument(Map<String, JsonNode> sanitisedDataToAttachDoc, Set<String> filterDocumentSet) {

        sanitisedDataToAttachDoc.forEach((field, jsonNodeValue) -> {
            if(jsonNodeValue !=null && (jsonNodeValue.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE )!= null || jsonNodeValue.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null)){

                JsonNode documentBinaryField = jsonNodeValue.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE);
                if (documentBinaryField != null) {
                    filterDocumentSet.add(documentBinaryField.asText().substring(documentBinaryField.asText().length() - 43, documentBinaryField.asText().length() - 7));

                } else {
                    JsonNode documentField = jsonNodeValue.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE);
                    filterDocumentSet.add(documentField.asText().substring(documentField.asText().length() - 36));
                }
            }

        });


    }

    public void filterDocumentMetaData(Set<String> filterDocumentSet){

        List<CaseDocument> caseDocumentList = documentMetadata.getDocuments().stream()
            .filter(document -> filterDocumentSet.contains(document.getId()))
            .collect(Collectors.toList());
        documentMetadata.setDocuments(caseDocumentList);

    }



}
