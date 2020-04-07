package uk.gov.hmcts.ccd.domain.service.createevent;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.ccd.ApplicationParams;
import uk.gov.hmcts.ccd.data.SecurityUtils;
import uk.gov.hmcts.ccd.data.casedetails.CachedCaseDetailsRepository;
import uk.gov.hmcts.ccd.data.casedetails.CaseAuditEventRepository;
import uk.gov.hmcts.ccd.data.casedetails.CaseDetailsRepository;
import uk.gov.hmcts.ccd.data.definition.CachedCaseDefinitionRepository;
import uk.gov.hmcts.ccd.data.definition.CaseDefinitionRepository;
import uk.gov.hmcts.ccd.data.user.CachedUserRepository;
import uk.gov.hmcts.ccd.data.user.UserRepository;
import uk.gov.hmcts.ccd.domain.model.aggregated.IdamUser;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseState;
import uk.gov.hmcts.ccd.domain.model.definition.CaseType;
import uk.gov.hmcts.ccd.domain.model.search.DocumentMetadata;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.ccd.domain.model.std.CaseDataContent;
import uk.gov.hmcts.ccd.domain.model.std.Event;
import uk.gov.hmcts.ccd.domain.service.callbacks.EventTokenService;
import uk.gov.hmcts.ccd.domain.service.common.CaseDataService;
import uk.gov.hmcts.ccd.domain.service.common.CaseService;
import uk.gov.hmcts.ccd.domain.service.common.CaseTypeService;
import uk.gov.hmcts.ccd.domain.service.common.EventTriggerService;
import uk.gov.hmcts.ccd.domain.service.common.SecurityClassificationService;
import uk.gov.hmcts.ccd.domain.service.common.UIDService;
import uk.gov.hmcts.ccd.domain.service.stdapi.AboutToSubmitCallbackResponse;
import uk.gov.hmcts.ccd.domain.service.stdapi.CallbackInvoker;
import uk.gov.hmcts.ccd.domain.service.validate.ValidateCaseFieldsOperation;
import uk.gov.hmcts.ccd.domain.types.sanitiser.CaseSanitiser;
import uk.gov.hmcts.ccd.endpoint.exceptions.BadRequestException;
import uk.gov.hmcts.ccd.endpoint.exceptions.CaseConcurrencyException;
import uk.gov.hmcts.ccd.endpoint.exceptions.DataParsingException;
import uk.gov.hmcts.ccd.endpoint.exceptions.ResourceNotFoundException;
import uk.gov.hmcts.ccd.endpoint.exceptions.ValidationException;
import uk.gov.hmcts.ccd.infrastructure.user.UserAuthorisation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.v2.V2;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocument;

@Service
public class CreateCaseEventService {

    private static final Logger LOG = LoggerFactory.getLogger(CreateCaseEventService.class);

    @Inject
    private HttpServletRequest request;
    private final RestTemplate restTemplate;
    private final ApplicationParams applicationParams;
    private final SecurityUtils securityUtils;
    private static final ObjectMapper mapper = new ObjectMapper();
    private final UserRepository userRepository;
    private final CaseDetailsRepository caseDetailsRepository;
    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseAuditEventRepository caseAuditEventRepository;
    private final EventTriggerService eventTriggerService;
    private final EventTokenService eventTokenService;
    private final CaseService caseService;
    private final CaseDataService caseDataService;
    private final CaseTypeService caseTypeService;
    private final CaseSanitiser caseSanitiser;
    private final CallbackInvoker callbackInvoker;
    private final UIDService uidService;
    private final SecurityClassificationService securityClassificationService;
    private final ValidateCaseFieldsOperation validateCaseFieldsOperation;
    private final UserAuthorisation userAuthorisation;
    private final Clock clock;
    public static final String CASE_DATA_PARSING_EXCEPTION = "Exception while extracting the document fields from Case payload";
    public static final String CONTENT_TYPE = "content-type";
    public static final String DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION = "The documents have been altered outside the create case transaction";

    @Inject
    public CreateCaseEventService(@Qualifier(CachedUserRepository.QUALIFIER) final UserRepository userRepository,
                                  @Qualifier(CachedCaseDetailsRepository.QUALIFIER) final CaseDetailsRepository caseDetailsRepository,
                                  @Qualifier(CachedCaseDefinitionRepository.QUALIFIER) final CaseDefinitionRepository caseDefinitionRepository,
                                  final CaseAuditEventRepository caseAuditEventRepository,
                                  final EventTriggerService eventTriggerService,
                                  final EventTokenService eventTokenService,
                                  final CaseService caseService,
                                  final CaseDataService caseDataService,
                                  final CaseTypeService caseTypeService,
                                  final CaseSanitiser caseSanitiser,
                                  final CallbackInvoker callbackInvoker,
                                  final UIDService uidService,
                                  final SecurityClassificationService securityClassificationService,
                                  final ValidateCaseFieldsOperation validateCaseFieldsOperation,
                                  final UserAuthorisation userAuthorisation,
                                  @Qualifier("utcClock") final Clock clock,
                                  @Qualifier("restTemplate") final RestTemplate restTemplate,
                                  ApplicationParams applicationParams,
                                  SecurityUtils securityUtils) {
        this.userRepository = userRepository;
        this.caseDetailsRepository = caseDetailsRepository;
        this.caseDefinitionRepository = caseDefinitionRepository;
        this.caseAuditEventRepository = caseAuditEventRepository;
        this.eventTriggerService = eventTriggerService;
        this.caseService = caseService;
        this.caseDataService = caseDataService;
        this.caseTypeService = caseTypeService;
        this.eventTokenService = eventTokenService;
        this.caseSanitiser = caseSanitiser;
        this.callbackInvoker = callbackInvoker;
        this.uidService = uidService;
        this.securityClassificationService = securityClassificationService;
        this.validateCaseFieldsOperation = validateCaseFieldsOperation;
        this.userAuthorisation = userAuthorisation;
        this.clock = clock;
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.restTemplate = restTemplate;
        this.applicationParams = applicationParams;
        this.securityUtils = securityUtils;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateCaseEventResult createCaseEvent(String caseReference, CaseDataContent content) {

        final CaseDetails caseDetails = getCaseDetails(caseReference);
        final CaseType caseType = caseDefinitionRepository.getCaseType(caseDetails.getCaseTypeId());
        final CaseEvent eventTrigger = findAndValidateCaseEvent(caseType, content.getEvent());
        final CaseDetails caseDetailsBefore = caseService.clone(caseDetails);
        String uid = userAuthorisation.getUserId();

        eventTokenService.validateToken(content.getToken(), uid, caseDetails, eventTrigger, caseType.getJurisdiction(), caseType);

        validatePreState(caseDetails, eventTrigger);
        mergeUpdatedFieldsToCaseDetails(content.getData(), caseDetails, eventTrigger, caseType);

        // remove hashcode before Callback Service

        boolean isApiVersion21 = request.getHeader(CONTENT_TYPE) != null
            && request.getHeader(CONTENT_TYPE).equals(V2.MediaType.CREATE_EVENT_2_1);

        Set<String> documentSetBeforeCallback = null;
        Set<String> documentAfterCallback = null;
        DocumentMetadata documentMetadata = null;

        if (isApiVersion21) {
            try {
                LOG.debug("Updating  case using Version 2.1 of case create API");
                documentSetBeforeCallback = new HashSet<>();
                documentMetadata = DocumentMetadata.builder()
                    .caseId(caseDetails.getReferenceAsString())
                    .jurisdictionId(caseDetails.getJurisdiction())
                    .caseTypeId(caseDetails.getCaseTypeId())
                    .documents(new ArrayList<>())
                    .build();

                caseSanitiser.extractDocumentFields(documentMetadata, caseDetails.getData(), documentSetBeforeCallback);
            }
            catch (Exception e) {
                LOG.error(CASE_DATA_PARSING_EXCEPTION);
                throw new DataParsingException(CASE_DATA_PARSING_EXCEPTION);
            }
        }

        AboutToSubmitCallbackResponse aboutToSubmitCallbackResponse = callbackInvoker.invokeAboutToSubmitCallback(eventTrigger,
            caseDetailsBefore,
            caseDetails,
            caseType,
            content.getIgnoreWarning());


        final Optional<String>
            newState = aboutToSubmitCallbackResponse.getState();

        validateCaseFieldsOperation.validateData(caseDetails.getData(), caseType);
        LocalDateTime timeNow = now();

        ///  all changes Start from  here

        if (isApiVersion21) {

                documentAfterCallback = new HashSet<>();
                // to remove hashcode before compute delta
                caseSanitiser.extractDocumentFields(documentMetadata, caseDetails.getData(),documentAfterCallback);

               //filter model based on callback response
               filterDocumentFields(documentMetadata, documentSetBeforeCallback, documentAfterCallback);

                //to sanitize data to prepare the Document object to send  attached doc case api
                final Set<String> filterDocumentSet = caseSanitiser.sanitizeForAttachedDocsToCase(caseDetailsBefore, caseDetails.getData());

                // to filter the DocumentMetaData based on sanitisedDataToAttachDoc
                List<CaseDocument> caseDocumentList = documentMetadata.getDocuments().stream()
                    .filter(document -> filterDocumentSet.contains(document.getId()))
                    .collect(Collectors.toList());
                documentMetadata.setDocuments(caseDocumentList);

        }


        final CaseDetails savedCaseDetails = saveCaseDetails(caseDetailsBefore, caseDetails, eventTrigger, newState, timeNow);
        saveAuditEventForCaseDetails(aboutToSubmitCallbackResponse, content.getEvent(), eventTrigger, savedCaseDetails, caseType, timeNow);

        // Case Document API Call to attach the case with respective document
        if (isApiVersion21) {
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
        return CreateCaseEventResult.caseEventWith()
            .caseDetailsBefore(caseDetailsBefore)
            .savedCaseDetails(savedCaseDetails)
            .eventTrigger(eventTrigger)
            .build();
    }

    private CaseEvent findAndValidateCaseEvent(final CaseType caseType,
                                               final Event event) {
        final CaseEvent eventTrigger = eventTriggerService.findCaseEvent(caseType, event.getEventId());
        if (eventTrigger == null) {
            throw new ValidationException(format("%s is not a known event ID for the specified case type %s", event.getEventId(), caseType.getId()));
        }
        return eventTrigger;
    }

    private void validatePreState(final CaseDetails caseDetails,
                                  final CaseEvent caseEvent) {
        if (!eventTriggerService.isPreStateValid(caseDetails.getState(), caseEvent)) {
            throw new ValidationException(
                format(
                    "Pre-state condition is not valid for case with state: %s; and event trigger: %s",
                    caseDetails.getState(),
                    caseEvent.getId()
                )
            );
        }
    }

    private CaseDetails getCaseDetails(final String caseReference) {
        if (!uidService.validateUID(caseReference)) {
            throw new BadRequestException("Case reference is not valid");
        }
        return caseDetailsRepository.findByReference(caseReference)
            .orElseThrow(() -> new ResourceNotFoundException(format("Case with reference %s could not be found", caseReference)));
    }

    private CaseDetails saveCaseDetails(CaseDetails caseDetailsBefore, final CaseDetails caseDetails,
                                        final CaseEvent eventTrigger,
                                        final Optional<String> state, LocalDateTime timeNow) {
        if (!state.isPresent() && !equalsIgnoreCase(CaseState.ANY, eventTrigger.getPostState())) {
            caseDetails.setState(eventTrigger.getPostState());
        }
        if (!caseDetails.getState().equalsIgnoreCase(caseDetailsBefore.getState())) {
            caseDetails.setLastStateModifiedDate(timeNow);
        }
        return caseDetailsRepository.set(caseDetails);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void mergeUpdatedFieldsToCaseDetails(final Map<String, JsonNode> data,
                                                 final CaseDetails caseDetails,
                                                 final CaseEvent caseEvent,
                                                 final CaseType caseType) {

        if (null != data) {

            final Map<String, JsonNode> sanitisedData = caseSanitiser.sanitise(caseType, data);

            for (Map.Entry<String, JsonNode> field : sanitisedData.entrySet()) {
                caseDetails.getData().put(field.getKey(), field.getValue());
            }
            caseDetails.setDataClassification(caseDataService.getDefaultSecurityClassifications(caseType, caseDetails.getData(), caseDetails.getDataClassification()));
        }
        caseDetails.setLastModified(now());
        if (!StringUtils.equalsAnyIgnoreCase(CaseState.ANY, caseEvent.getPostState())) {
            caseDetails.setState(caseEvent.getPostState());
        }
    }

    private void saveAuditEventForCaseDetails(final AboutToSubmitCallbackResponse aboutToSubmitCallbackResponse,
                                              final Event event,
                                              final CaseEvent eventTrigger,
                                              final CaseDetails caseDetails,
                                              final CaseType caseType, LocalDateTime timeNow) {
        final IdamUser user = userRepository.getUser();
        final CaseState caseState = caseTypeService.findState(caseType, caseDetails.getState());
        final AuditEvent auditEvent = new AuditEvent();

        auditEvent.setEventId(event.getEventId());
        auditEvent.setEventName(eventTrigger.getName());
        auditEvent.setSummary(event.getSummary());
        auditEvent.setDescription(event.getDescription());
        auditEvent.setCaseDataId(caseDetails.getId());
        auditEvent.setData(caseDetails.getData());
        auditEvent.setStateId(caseDetails.getState());
        auditEvent.setStateName(caseState.getName());
        auditEvent.setCaseTypeId(caseType.getId());
        auditEvent.setCaseTypeVersion(caseType.getVersion().getNumber());
        auditEvent.setUserId(user.getId());
        auditEvent.setUserLastName(user.getSurname());
        auditEvent.setUserFirstName(user.getForename());
        auditEvent.setCreatedDate(timeNow);
        auditEvent.setSecurityClassification(securityClassificationService.getClassificationForEvent(caseType, eventTrigger));
        auditEvent.setDataClassification(caseDetails.getDataClassification());
        auditEvent.setSignificantItem(aboutToSubmitCallbackResponse.getSignificantItem());

        caseAuditEventRepository.set(auditEvent);
    }

    private void filterDocumentFields(DocumentMetadata documentMetadata, Set<String> documentSetBeforeCallback, Set<String> documentSetAfterCallback) {
        try {
            //The below change is STRICTLY for LOCAL testing purpose. It needs to be removed in the PR environment
            documentSetAfterCallback.addAll(documentSetBeforeCallback);
            //PLEASE REMOVE ABOVE line in PR environment


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

}
