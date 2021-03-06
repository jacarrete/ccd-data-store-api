package uk.gov.hmcts.ccd.data.casedetails.search;

public class MetaDataCriterion extends Criterion {

    public MetaDataCriterion(String field, String soughtValue) {
        super(field, soughtValue);
    }

    @Override
    public String buildClauseString(int position, String operation) {
        return this.getField() + operation + POSITION_PREFIX + position;
    }

}
