package com.bfhl.billextraction.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BillExtractionResponse {

    @JsonProperty("is_success")
    private boolean isSuccess;

    @JsonProperty("message")
    private String message;

    @JsonProperty("token_usage")
    private TokenUsage tokenUsage;

    @JsonProperty("data")
    private ExtractionData data;

    public static BillExtractionResponse success(ExtractionData data, TokenUsage usage) {
        BillExtractionResponse res = new BillExtractionResponse();
        res.isSuccess = true;
        res.message = null;
        res.tokenUsage = usage;
        res.data = data;
        return res;
    }

    public static BillExtractionResponse failure(String message) {
        BillExtractionResponse res = new BillExtractionResponse();
        res.isSuccess = false;
        res.message = message;
        res.tokenUsage = null;
        res.data = null;
        return res;
    }
}

