package uk.gov.hmcts.ccd.datastore.tests.fixture;

import uk.gov.hmcts.ccd.datastore.tests.fixture.AATSearchCaseType.CaseData;

/**
 * Build pre-defined cases.
 */
public interface AATSearchCaseBuilder {

    /**
     * An empty case with no data.
     */
    interface EmptyCase {
        static CaseData build() {
            return CaseData.builder()
                .build();
        }
    }

    /**
     * An case with data in every fields.
     */
    interface FullCase {
        String TEXT = "Some Text";
        String NUMBER = "164528";
        String YES_OR_NO = "Yes";
        String PHONE_UK = "07123456789";
        String EMAIL = "ccd@hmcts.net";
        String MONEY_GBP = "4200";
        String DATE = "2017-02-13";
        String DATE_TIME = "1988-07-07T22:20:00";
        String TEXT_AREA = "Line1\nLine2";
        String FIXED_LIST = "VALUE3";
        String[] MULTI_SELECT_LIST = new String[]{"OPTION2", "OPTION4"};
        String COLLECTION_VALUE_1 = "Alias 1";
        String COLLECTION_VALUE_2 = "Alias 2";
        String COMPLEX_TEXT = "Nested text";
        String COMPLEX_FIXED_LIST = "VALUE2";
        String ADDRESS_LINE_1 = "102 Petty France";
        String ADDRESS_LINE_2 = "CCD";
        String ADDRESS_LINE_3 = "c/o HMCTS Reform";
        String ADDRESS_POST_TOWN = "Westminster";
        String ADDRESS_COUNTY = "Greater London";
        String ADDRESS_POSTCODE = "SW1H 9AJ";
        String ADDRESS_COUNTRY = "UK";

        static CaseData build() {
            return CaseData.builder()
                .textField(TEXT)
                .numberField(NUMBER)
                .yesOrNoField(YES_OR_NO)
                .phoneUKField(PHONE_UK)
                .emailField(EMAIL)
                .moneyGBPField(MONEY_GBP)
                .dateField(DATE)
                .dateTimeField(DATE_TIME)
                .textAreaField(TEXT_AREA)
                .fixedListField(FIXED_LIST)
                .multiSelectListField(MULTI_SELECT_LIST)
                .collectionField(new AATSearchCaseType.CollectionItem[]{
                    new AATSearchCaseType.CollectionItem(null, COLLECTION_VALUE_1),
                    new AATSearchCaseType.CollectionItem(null, COLLECTION_VALUE_2)
                })
                .complexField(new AATSearchCaseType.ComplexType(COMPLEX_TEXT, COMPLEX_FIXED_LIST))
                .addressUKField(
                    AATSearchCaseType.AddressUKField.builder()
                        .addressLine1(ADDRESS_LINE_1)
                        .addressLine2(ADDRESS_LINE_2)
                        .addressLine3(ADDRESS_LINE_3)
                        .postTown(ADDRESS_POST_TOWN)
                        .county(ADDRESS_COUNTY)
                        .postCode(ADDRESS_POSTCODE)
                        .country(ADDRESS_COUNTRY)
                        .build()
                )
                .build();
        }
    }
}
