package com.bajajfinserv.billextraction.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "pdf.processing")
@Data
public class PdfProcessingConfig {

    // DPI settings
    private int defaultDpi = 300;
    private int largePdfDpi = 200;
    private int hugeFileDpi = 150;

    // File size thresholds (MB)
    private long largeFileThresholdMb = 5;
    private long hugeFileThresholdMb = 15;

    // Page thresholds
    private int manyPagesThreshold = 8;
    private int tooManyPagesThreshold = 15;

    // Batch processing
    private int maxPagesPerBatch = 5;
    private int parallelRenderingThreads = 4;

    // Timeouts (seconds)
    private int pdfRenderTimeout = 120;
    private int apiCallTimeout = 60;
    private int totalProcessingTimeout = 150;
}
