package com.bfhl.billextraction.controller;

import com.bfhl.billextraction.model.BillExtractionRequest;
import com.bfhl.billextraction.model.BillExtractionResponse;
import com.bfhl.billextraction.service.BillExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/")
@Slf4j
public class BillExtractionController {

    @Autowired
    private BillExtractionService extractionService;

    @PostMapping("/extract-bill-data")
    public ResponseEntity<BillExtractionResponse> extractBillData(
            @RequestBody BillExtractionRequest request) {

        try {
            log.info("Received extraction request for document: {}", request.getDocumentUrl());

            // NEW (CORRECT): pass String documentUrl
            BillExtractionResponse response =
                    extractionService.extractBillData(request.getDocumentUrl());

            log.info("Extraction completed successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error during extraction: ", e);
            return ResponseEntity.ok(
                    BillExtractionResponse.failure("Extraction failed: " + e.getMessage())
            );
        }
    }


    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("API is running");
    }
}
