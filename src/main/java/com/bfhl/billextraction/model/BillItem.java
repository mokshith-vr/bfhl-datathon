package com.bfhl.billextraction.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data

@NoArgsConstructor
public class BillItem {

    @JsonProperty("item_name")
    private String itemName;

    @JsonProperty("item_amount")
    private Double itemAmount;

    @JsonProperty("item_rate")
    private Double itemRate;

    @JsonProperty("item_quantity")
    private Double itemQuantity;
}
