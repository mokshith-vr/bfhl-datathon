package com.bfhl.billextraction.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BillExtractionRequest {
    @JsonProperty("document")
    private String documentUrl;
}
