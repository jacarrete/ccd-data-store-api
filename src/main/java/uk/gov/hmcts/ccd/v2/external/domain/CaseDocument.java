package uk.gov.hmcts.ccd.v2.external.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.domain.model.definition.Document;

import java.util.List;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseDocument {
    private String url;
    private String name;
    private String type;
    private String description;
    private String id;
    private List<Permission> permissions;
    @JsonIgnore
    private String hashedToken;
}