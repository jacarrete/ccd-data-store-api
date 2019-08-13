package uk.gov.hmcts.ccd.domain.service.aggregated;

import java.util.*;
import java.util.stream.Collectors;

import static uk.gov.hmcts.ccd.domain.service.common.AccessControlService.CAN_READ;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.casedetails.search.MetaData;
import uk.gov.hmcts.ccd.data.casedetails.search.SortDirection;
import uk.gov.hmcts.ccd.data.casedetails.search.SortOrderField;
import uk.gov.hmcts.ccd.data.definition.UIDefinitionRepository;
import uk.gov.hmcts.ccd.data.draft.DraftAccessException;
import uk.gov.hmcts.ccd.data.user.CachedUserRepository;
import uk.gov.hmcts.ccd.data.user.UserRepository;
import uk.gov.hmcts.ccd.domain.model.definition.*;
import uk.gov.hmcts.ccd.domain.model.search.SearchResultView;
import uk.gov.hmcts.ccd.domain.service.getdraft.DefaultGetDraftsOperation;
import uk.gov.hmcts.ccd.domain.service.getdraft.GetDraftsOperation;
import uk.gov.hmcts.ccd.domain.service.search.CreatorSearchOperation;
import uk.gov.hmcts.ccd.domain.service.search.SearchOperation;

@Service
public class SearchQueryOperation {
    protected static final String NO_ERROR = null;
    public static final String WORKBASKET = "WORKBASKET";

    private static final String CASE_TYPE_DIVORCE = "DIVORCE";
    private static final String CASE_DATA_COLUMN_LAST_MODIFIED = "last_modified";
    private static final String CASE_DATA_ENTITY_FIELD_LAST_MODIFIED = "lastModified";

    private final MergeDataToSearchResultOperation mergeDataToSearchResultOperation;
    private final GetCaseTypeOperation getCaseTypeOperation;
    private final SearchOperation searchOperation;
    private final GetDraftsOperation getDraftsOperation;
    private final UIDefinitionRepository uiDefinitionRepository;
    private final UserRepository userRepository;

    @Autowired
    public SearchQueryOperation(@Qualifier(CreatorSearchOperation.QUALIFIER) final SearchOperation searchOperation,
                                final MergeDataToSearchResultOperation mergeDataToSearchResultOperation,
                                @Qualifier(AuthorisedGetCaseTypeOperation.QUALIFIER) final GetCaseTypeOperation getCaseTypeOperation,
                                @Qualifier(DefaultGetDraftsOperation.QUALIFIER) GetDraftsOperation getDraftsOperation,
                                final UIDefinitionRepository uiDefinitionRepository,
                                @Qualifier(CachedUserRepository.QUALIFIER) final UserRepository userRepository) {
        this.searchOperation = searchOperation;
        this.mergeDataToSearchResultOperation = mergeDataToSearchResultOperation;
        this.getCaseTypeOperation = getCaseTypeOperation;
        this.getDraftsOperation = getDraftsOperation;
        this.uiDefinitionRepository = uiDefinitionRepository;
        this.userRepository = userRepository;
    }

    public SearchResultView execute(final String view,
                                    final MetaData metadata,
                                    final Map<String, String> queryParameters) {

        Optional<CaseType> caseType = this.getCaseTypeOperation.execute(metadata.getCaseTypeId(), CAN_READ);

        if (!caseType.isPresent()) {
            return new SearchResultView(Collections.emptyList(), Collections.emptyList(), NO_ERROR);
        }

        final SearchResult searchResult = getSearchResult(caseType.get(), view);
        addSortOrders(metadata, queryParameters, searchResult);

        final List<CaseDetails> cases = searchOperation.execute(metadata, queryParameters);

        String draftResultError = NO_ERROR;
        List<CaseDetails> draftsAndCases = Lists.newArrayList();
        if (StringUtils.equalsAnyIgnoreCase(WORKBASKET, view) && caseType.get().hasDraftEnabledEvent()) {
            try {
                draftsAndCases = getDraftsOperation.execute(metadata);
            } catch (DraftAccessException dae) {
                draftResultError = dae.getMessage();
            }
        }
        draftsAndCases.addAll(cases);
        return mergeDataToSearchResultOperation.execute(caseType.get(), searchResult, draftsAndCases, draftResultError);
    }

    private SearchResult getSearchResult(final CaseType caseType, final String view) {
        if (WORKBASKET.equalsIgnoreCase(view)) {
            return uiDefinitionRepository.getWorkBasketResult(caseType.getId());
        }
        return uiDefinitionRepository.getSearchResult(caseType.getId());
    }

    private void addSortOrders(MetaData metadata, Map<String, String> queryParameters, SearchResult searchResult) {
        //Some (ugly) hardcoding (RDM-4636), until we provide feature for default sorting of search results via definition
        if (CASE_TYPE_DIVORCE.equalsIgnoreCase(metadata.getCaseTypeId())) {
            if (queryParameters.isEmpty()) {
                metadata.addSortOrderField(getDivorceSortOrder(CASE_DATA_ENTITY_FIELD_LAST_MODIFIED, metadata.getSortDirection(), false));
            } else {
                metadata.addSortOrderField(getDivorceSortOrder(CASE_DATA_COLUMN_LAST_MODIFIED, metadata.getSortDirection(), true));
            }
        } else {
            metadata.setSortOrderFields(getSortOrders(searchResult));
        }
    }

    private SortOrderField getDivorceSortOrder(String column, Optional<String> direction, boolean metadata) {
        return SortOrderField.sortOrderWith()
            .caseFieldId(column)
            .direction(SortDirection.fromOptionalString(direction).name())
            .metadata(metadata)
            .build();
    }

    private List<SortOrderField> getSortOrders(SearchResult searchResult) {
        return Arrays.stream(searchResult.getFields())
            .filter(searchResultField -> hasSortField(searchResultField))
            .filter(searchResultField -> filterByRole(searchResultField))
            .sorted(Comparator.comparing(srf -> srf.getSortOrder().getPriority()))
            .map(this::getSortOrder)
            .collect(Collectors.toList());
    }

    private boolean hasSortField(SearchResultField searchResultField) {
        SortOrder sortOrder = searchResultField.getSortOrder();
        return sortOrder != null && sortOrder.getDirection() != null && sortOrder.getPriority() != null;
    }

    private boolean filterByRole(SearchResultField resultField) {
        return StringUtils.isEmpty(resultField.getRole()) || userRepository.getUserRoles().contains(resultField.getRole());
    }

    private SortOrderField getSortOrder(SearchResultField searchResultField) {
        return SortOrderField.sortOrderWith()
            .caseFieldId(buildCaseFieldId(searchResultField))
            .metadata(searchResultField.isMetadata())
            .direction(searchResultField.getSortOrder().getDirection())
            .build();
    }

    private String buildCaseFieldId(SearchResultField searchResultField) {
        if (StringUtils.isNotBlank(searchResultField.getCaseFieldPath())) {
            return searchResultField.getCaseFieldId() + '.' + searchResultField.getCaseFieldPath();
        }
        return searchResultField.getCaseFieldId();
    }

}
