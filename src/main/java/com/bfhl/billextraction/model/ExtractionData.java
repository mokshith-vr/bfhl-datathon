package com.bfhl.billextraction.model;



import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionData {

    @JsonProperty("pagewise_line_items")
    private List<PageWiseLineItems> pagewiseLineItems;

    @JsonProperty("total_item_count")
    private Integer totalItemCount;
}
