package com.bfhl.billextraction.model;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageWiseLineItems {

    @JsonProperty("page_no")
    private String pageNo;

    @JsonProperty("page_type")
    private String pageType; // "Bill Detail" | "Final Bill" | "Pharmacy"

    @JsonProperty("bill_items")
    private List<BillItem> billItems;
}
