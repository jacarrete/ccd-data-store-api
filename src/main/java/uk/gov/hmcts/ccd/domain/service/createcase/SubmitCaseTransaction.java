package uk.gov.hmcts.ccd.domain.service.createcase;

import static javax.transaction.Transactional.TxType.REQUIRES_NEW;
import static uk.gov.hmcts.ccd.data.caseaccess.GlobalCaseRole.CREATOR;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.common.TriFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.caseaccess.CachedCaseUserRepository;
import uk.gov.hmcts.ccd.data.caseaccess.CaseUserRepository;
import uk.gov.hmcts.ccd.data.casedetails.CachedCaseDetailsRepository;
import uk.gov.hmcts.ccd.data.casedetails.CaseAuditEventRepository;
import uk.gov.hmcts.ccd.data.casedetails.CaseDetailsRepository;
import uk.gov.hmcts.ccd.domain.model.aggregated.IdamUser;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseState;
import uk.gov.hmcts.ccd.domain.model.definition.CaseType;
import uk.gov.hmcts.ccd.domain.model.search.CaseDocumentsMetadata;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.ccd.domain.model.std.Event;
import uk.gov.hmcts.ccd.domain.service.common.CaseTypeService;
import uk.gov.hmcts.ccd.domain.service.common.SecurityClassificationService;
import uk.gov.hmcts.ccd.domain.service.common.UIDService;
import uk.gov.hmcts.ccd.domain.service.getcasedocument.CaseDocumentAttachOperation;
import uk.gov.hmcts.ccd.domain.service.stdapi.AboutToSubmitCallbackResponse;
import uk.gov.hmcts.ccd.domain.service.stdapi.CallbackInvoker;
import uk.gov.hmcts.ccd.endpoint.exceptions.DataParsingException;
import uk.gov.hmcts.ccd.endpoint.exceptions.ReferenceKeyUniqueConstraintException;
import uk.gov.hmcts.ccd.infrastructure.user.UserAuthorisation;
import uk.gov.hmcts.ccd.infrastructure.user.UserAuthorisation.AccessLevel;
import uk.gov.hmcts.ccd.v2.V2;

@Service
class SubmitCaseTransaction {

    private static final Logger LOG = LoggerFactory.getLogger(SubmitCaseTransaction.class);

    private HttpServletRequest request;
    private final CaseDetailsRepository caseDetailsRepository;
    private final CaseAuditEventRepository caseAuditEventRepository;
    private final CaseTypeService caseTypeService;
    private final CallbackInvoker callbackInvoker;
    private final UIDService uidService;
    private final SecurityClassificationService securityClassificationService;
    private final CaseUserRepository caseUserRepository;
    private final UserAuthorisation userAuthorisation;
    private final CaseDocumentAttachOperation caseDocumentAttachOperation;

    public static final String COMPLEX = "Complex";
    public static final String COLLECTION = "Collection";
    public static final String DOCUMENT = "Document";
    public static final String DOCUMENT_CASE_FIELD_URL_ATTRIBUTE = "document_url";
    public static final String DOCUMENT_CASE_FIELD_BINARY_ATTRIBUTE = "document_binary_url";
    public static final String BAD_REQUEST_EXCEPTION_DOCUMENT_INVALID = "DocumentId is not valid";
    public static final String HASH_CODE_STRING = "hashcode";
    public static final String CONTENT_TYPE = "content-type";
    public static final String BINARY = "/binary";
    public static final String CASE_DATA_PARSING_EXCEPTION = "Exception while extracting the document fields from Case payload";
    public static final String DOCUMENTS_ALTERED_OUTSIDE_TRANSACTION = "The documents have been altered outside the create case transaction";

    @Inject
    public SubmitCaseTransaction(@Qualifier(CachedCaseDetailsRepository.QUALIFIER) final CaseDetailsRepository caseDetailsRepository,
                                 final CaseAuditEventRepository caseAuditEventRepository,
                                 final CaseTypeService caseTypeService,
                                 final CallbackInvoker callbackInvoker,
                                 final UIDService uidService,
                                 final SecurityClassificationService securityClassificationService,
                                 final @Qualifier(CachedCaseUserRepository.QUALIFIER) CaseUserRepository caseUserRepository,
                                 final UserAuthorisation userAuthorisation,
                                 HttpServletRequest request,
                                 CaseDocumentAttachOperation caseDocumentAttachOperation
                                ) {
        this.request = request;
        this.caseDetailsRepository = caseDetailsRepository;
        this.caseAuditEventRepository = caseAuditEventRepository;
        this.caseTypeService = caseTypeService;
        this.callbackInvoker = callbackInvoker;
        this.uidService = uidService;
        this.securityClassificationService = securityClassificationService;
        this.caseUserRepository = caseUserRepository;
        this.userAuthorisation = userAuthorisation;
        this.caseDocumentAttachOperation = caseDocumentAttachOperation;
    }

    @Transactional(REQUIRES_NEW)
    @Retryable(
        value = {ReferenceKeyUniqueConstraintException.class},
        maxAttempts = 2,
        backoff = @Backoff(delay = 50)
    )
    public CaseDetails submitCase(Event event,
                                  CaseType caseType,
                                  IdamUser idamUser,
                                  CaseEvent eventTrigger,
                                  CaseDetails newCaseDetails, Boolean ignoreWarning) {

        final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        newCaseDetails.setCreatedDate(now);
        newCaseDetails.setLastStateModifiedDate(now);
        newCaseDetails.setReference(Long.valueOf(uidService.generateUID()));

        boolean isApiVersion21 = request.getContentType() != null
            && request.getContentType().equals(V2.MediaType.CREATE_CASE_2_1);

        if (isApiVersion21) {
            caseDocumentAttachOperation.caseDocumentAttachOperation(newCaseDetails);
        }

        /*
            About to submit

            TODO: Ideally, the callback should be outside of the transaction. However, it requires the case UID to have
            been assigned and the UID generation has to be part of a retryable transaction in order to recover from collisions.
         */
        AboutToSubmitCallbackResponse aboutToSubmitCallbackResponse =
            callbackInvoker.invokeAboutToSubmitCallback(eventTrigger, null, newCaseDetails, caseType, ignoreWarning);

        final CaseDetails savedCaseDetails =
            saveAuditEventForCaseDetails(aboutToSubmitCallbackResponse, event, caseType, idamUser, eventTrigger, newCaseDetails);

        if (AccessLevel.GRANTED.equals(userAuthorisation.getAccessLevel())) {
            caseUserRepository.grantAccess(Long.valueOf(savedCaseDetails.getId()),
                                           idamUser.getId(),
                                           CREATOR.getRole());
        }

        if (isApiVersion21) {
            caseDocumentAttachOperation.afterCallbackPrepareDocumentMetaData(newCaseDetails);
            caseDocumentAttachOperation.filterDocumentFields();
            caseDocumentAttachOperation.restCallToAttachCaseDocuments();
        }

        return savedCaseDetails;
    }

    private CaseDetails saveAuditEventForCaseDetails(AboutToSubmitCallbackResponse response,
                                                     Event event,
                                                     CaseType caseType,
                                                     IdamUser idamUser,
                                                     CaseEvent eventTrigger,
                                                     CaseDetails newCaseDetails) {

        final CaseDetails savedCaseDetails = caseDetailsRepository.set(newCaseDetails);
        final AuditEvent auditEvent = new AuditEvent();
        auditEvent.setEventId(event.getEventId());
        auditEvent.setEventName(eventTrigger.getName());
        auditEvent.setSummary(event.getSummary());
        auditEvent.setDescription(event.getDescription());
        auditEvent.setCaseDataId(savedCaseDetails.getId());
        auditEvent.setData(savedCaseDetails.getData());
        auditEvent.setStateId(savedCaseDetails.getState());
        CaseState caseState = caseTypeService.findState(caseType, savedCaseDetails.getState());
        auditEvent.setStateName(caseState.getName());
        auditEvent.setCaseTypeId(caseType.getId());
        auditEvent.setCaseTypeVersion(caseType.getVersion().getNumber());
        auditEvent.setUserId(idamUser.getId());
        auditEvent.setUserLastName(idamUser.getSurname());
        auditEvent.setUserFirstName(idamUser.getForename());
        auditEvent.setCreatedDate(newCaseDetails.getCreatedDate());
        auditEvent.setSecurityClassification(securityClassificationService.getClassificationForEvent(caseType, eventTrigger));
        auditEvent.setDataClassification(savedCaseDetails.getDataClassification());
        auditEvent.setSignificantItem(response.getSignificantItem());

        caseAuditEventRepository.set(auditEvent);
        return savedCaseDetails;
    }
}
