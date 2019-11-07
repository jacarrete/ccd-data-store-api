package uk.gov.hmcts.ccd.fta.data;

import java.util.Map;

public class RequestData {

    private Map<String, Object> headers;

    private Map<String, Object> pathVariables;

    private Map<String, Object> queryParams;

    private byte[] body;

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> headers) {
        this.headers = headers;
    }

    public Map<String, Object> getPathVariables() {
        return pathVariables;
    }

    public void setPathVariables(Map<String, Object> pathVariables) {
        this.pathVariables = pathVariables;
    }

    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

}