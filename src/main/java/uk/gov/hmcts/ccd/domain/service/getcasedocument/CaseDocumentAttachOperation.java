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
import uk.gov.hmcts.ccd.endpoint.exceptions.BadRequestException;
import uk.gov.hmcts.ccd.endpoint.exceptions.CaseConcurrencyException;
import uk.gov.hmcts.ccd.endpoint.exceptions.DataParsingException;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocument;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CaseDocumentAttachOperation {

    private static final Logger LOG = LoggerFactory.getLogger(CaseDocumentAttachOperation.class);

    Map<String,String> documentSetBeforeCallback = null;
    Map<String,String> documentAfterCallback = null;
    DocumentMetadata documentMetadata = null;
    public static final String CASE_DATA_PARSING_EXCEPTION = "Exception while extracting the document fields from Case payload";
    public static final String COMPLEX = "Complex";
    public static final String COLLECTION = "Collection";
    public static final String DOCUMENT = "Document";
    public static final String DOCUMENT_CASE_FIELD_URL_ATTRIBUTE = "document_url";
    public static final String DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE = "document_binary_url";
    public static final String HASH_CODE_STRING = "hashToken";
    private final RestTemplate restTemplate;
    private final ApplicationParams applicationParams;
    private final SecurityUtils securityUtils;
    public static final String DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION = "The documents have been altered outside the create case transaction";
    public static final String BINARY = "/binary";

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
            documentSetBeforeCallback = new HashMap<>();
            extractDocumentFieldsBeforeCallback(caseDetails.getData(), documentSetBeforeCallback);
        }
        catch (Exception e) {
            LOG.error(CASE_DATA_PARSING_EXCEPTION);
            throw new DataParsingException(CASE_DATA_PARSING_EXCEPTION);
        }
    }

    public void  afterCallbackPrepareDocumentMetaData(CaseDetails caseDetails){
        try {
            documentAfterCallback = new HashMap<>();
            // to remove hashcode before compute delta
            extractDocumentFieldsAfterCallback(documentMetadata, caseDetails.getData(),documentAfterCallback);
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

    public void extractDocumentFieldsBeforeCallback(Map<String, JsonNode> data, Map<String,String> documentMap) {
        data.forEach((field, jsonNode) -> {
            //Check if the field consists of Document at any level, e.g. Complex fields can also have documents.
            //This quick check will reduce the processing time as most of filtering will be done at top level.
            //****** Every document should have hashcode, else throw error
            if (jsonNode != null && isDocumentField(jsonNode))  {
                if (jsonNode.get(HASH_CODE_STRING) == null) {
                    throw new BadRequestException("The document does not has the hashcode");
                }
                String documentId = extractDocumentId(jsonNode);
                documentMap.put(documentId,jsonNode.get(HASH_CODE_STRING).asText());
                if (jsonNode instanceof ObjectNode) {
                    ((ObjectNode) jsonNode).remove(HASH_CODE_STRING);
                }

            } else {
                jsonNode.fields().forEachRemaining
                    (node -> extractDocumentFieldsBeforeCallback(
                        Collections.singletonMap(node.getKey(), node.getValue()), documentMap));
            }
        });
    }
    public void extractDocumentFieldsAfterCallback(DocumentMetadata documentMetadata, Map<String, JsonNode> data, Map<String,String> documentMap) {
        data.forEach((field, jsonNode) -> {
            //Check if the field consists of Document at any level, e.g. Complex fields can also have documents.
            //This quick check will reduce the processing time as most of filtering will be done at top level.
            //****** Every document should have hashcode, else throw error
            if (jsonNode != null && isDocumentField(jsonNode)) {
                if (jsonNode.get(HASH_CODE_STRING) == null) {
                    throw new BadRequestException("The document does not has the hashcode");
                }
                String documentId = extractDocumentId(jsonNode);
                documentMap.put(documentId,jsonNode.get(HASH_CODE_STRING).asText());
                documentMetadata.getDocuments().add(CaseDocument
                    .builder()
                    .id(documentId)
                    .hashToken(jsonNode.get(HASH_CODE_STRING).asText())
                    .build());


                if (jsonNode instanceof ObjectNode) {
                    ((ObjectNode) jsonNode).remove(HASH_CODE_STRING);
                }

            } else {
                jsonNode.fields().forEachRemaining
                    (node -> extractDocumentFieldsBeforeCallback(
                        Collections.singletonMap(node.getKey(), node.getValue()), documentMap));
            }
        });
    }

    private boolean isDocumentField(JsonNode jsonNode) {
        return jsonNode.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null
            || jsonNode.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null;
    }
    public String extractDocumentId(JsonNode jsonNode) {
        //Document Binary URL is preferred.
        JsonNode documentField = jsonNode.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null ?
            jsonNode.get(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) :
            jsonNode.get(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE);
        if (documentField.asText().contains(BINARY)) {
            return documentField.asText().substring(documentField.asText().length() - 43, documentField.asText().length() - 7);
        } else {
            return documentField.asText().substring(documentField.asText().length() - 36);
        }
    }


    private void filterDocumentFields(DocumentMetadata documentMetadata, Map<String,String> documentSetBeforeCallback, Map<String,String> documentSetAfterCallback) {
        try {
            //Below line should be remove before promoting to PR env.
            documentSetAfterCallback.putAll(documentSetBeforeCallback);

            //find documents which are intersection of Before and after callback
            Map<String, String> filteredDocumentSet = documentSetAfterCallback.entrySet().stream()
                .filter(entry->documentSetBeforeCallback.containsKey(entry.getKey()) && documentSetBeforeCallback.get(entry.getKey()).equals(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));


            //Add the intersection to aftercallback list. Now, afterCallbackList will have the documents from
            //Callback response
            // + original documents which have not been removed by the callback
            // + Any new documents which are added by callback response
            //This code should drop any documents which were removed by the callback
            documentSetAfterCallback.putAll(filteredDocumentSet);
            List<CaseDocument>  filterCaseDocument = documentMetadata.getDocuments().stream().filter(caseDocument->documentSetAfterCallback.containsKey(caseDocument.getId())&& documentSetAfterCallback.get(caseDocument.getId()).equals(caseDocument.getHashToken())).collect(Collectors.toList());
            documentMetadata.setDocuments(filterCaseDocument);
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

            if (caseDetails.getData().containsKey(key) && (value.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null)) {
                if(!value.equals(caseDetails.getData().get(key)))
                {
                    documentsDifference.put(key,value);
                }
            } else if (value.findValue(DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE) != null || value.findValue(DOCUMENT_CASE_FIELD_URL_ATTRIBUTE) != null){
                documentsDifference.put(key,value);
            }
        });
        //Find documentId based on filter Map. So that I can filter the DocumentMetaData Object before calling the case document am Api.
        findDocumenstId(documentsDifference,filterDocumentSet);
        return filterDocumentSet;
    }
    private void findDocumenstId(Map<String, JsonNode> sanitisedDataToAttachDoc, Set<String> filterDocumentSet) {

        sanitisedDataToAttachDoc.forEach((field, jsonNode) -> {
            //Check if the field consists of Document at any level, e.g. Complex fields can also have documents.
            //This quick check will reduce the processing time as most of filtering will be done at top level.
            //****** Every document should have hashcode, else throw error
            if (jsonNode != null && isDocumentField(jsonNode)) {
                String documentId = extractDocumentId(jsonNode);
                filterDocumentSet.add(documentId);

            } else {
                jsonNode.fields().forEachRemaining
                    (node -> findDocumenstId(
                        Collections.singletonMap(node.getKey(), node.getValue()), filterDocumentSet));
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
