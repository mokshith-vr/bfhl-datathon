package com.bfhl.billextraction.service;

import com.bfhl.billextraction.model.BillExtractionResponse;
import com.bfhl.billextraction.model.BillItem;
import com.bfhl.billextraction.model.ExtractionData;
import com.bfhl.billextraction.model.PageWiseLineItems;
import com.bfhl.billextraction.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class BillExtractionService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${openai.api.url}")
    private String openaiApiUrl;

    // Configuration constants
    private static final int DEFAULT_DPI = 300;
    private static final int LARGE_FILE_DPI = 200;
    private static final int HUGE_FILE_DPI = 150;
    private static final long LARGE_FILE_MB = 5;
    private static final long HUGE_FILE_MB = 15;
    private static final int BATCH_SIZE = 3;
    private static final int PARALLEL_THREADS = 4;

    private static class OpenAiResult {
        final String content;
        final int inputTokens;
        final int outputTokens;

        OpenAiResult(String content, int inputTokens, int outputTokens) {
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }
    /**
     * Main entry point
     */
    public BillExtractionResponse extractBillData(String documentUrl) {
        long startTime = System.currentTimeMillis();
        log.info("=== Starting extraction for: {}", documentUrl);

        // cumulative token usage
        TokenUsage totalUsage = new TokenUsage(0, 0, 0);

        try {
            File pdfFile = downloadPdf(documentUrl);
            log.info("Downloaded PDF: {:.2f} MB", pdfFile.length() / (1024.0 * 1024.0));

            ExtractionData data = processPdfDocument(pdfFile, totalUsage);
            validateAndReconcile(data);

            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("=== Extraction SUCCESS in {:.1f}s ===", elapsedMs / 1000.0);

            return BillExtractionResponse.success(data, totalUsage);

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("=== Extraction FAILED after {:.1f}s ===", elapsedMs / 1000.0, e);
            return BillExtractionResponse.failure("Extraction failed: " + e.getMessage());
        }
    }

    private File downloadPdf(String pdfUrl) throws IOException {
        int maxRetries = 3;
        Exception lastException = null;

        // Normalize: encode spaces as %20
        String normalizedUrl = pdfUrl.replace(" ", "%20");

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("Download attempt {}/{}: {}", attempt, maxRetries, normalizedUrl);
                URL url = new URL(normalizedUrl);

                File tempFile = Files.createTempFile("bill_", ".pdf").toFile();
                try (InputStream in = url.openStream();
                     OutputStream out = new FileOutputStream(tempFile)) {
                    in.transferTo(out);
                }
                return tempFile;
            } catch (IOException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new IOException("Failed to download PDF after " + maxRetries + " attempts", lastException);
    }


    private PDDocument loadPdfRobustly(File pdfFile) throws IOException {
        log.debug("Loading PDF: {} bytes", pdfFile.length());

        // Strategy 1: Standard load
        try {
            PDDocument doc = PDDocument.load(pdfFile);
            log.info("✓ PDF loaded (standard): {} pages", doc.getNumberOfPages());
            return doc;
        } catch (IOException e) {
            log.warn("Standard load failed: {}", e.getMessage());
        }

        // Strategy 2: Lenient using temp file
        try (InputStream is = new FileInputStream(pdfFile)) {
            PDDocument doc = PDDocument.load(is, (String) null, MemoryUsageSetting.setupTempFileOnly());
            log.info("✓ PDF loaded (lenient): {} pages", doc.getNumberOfPages());
            return doc;
        } catch (IOException e) {
            log.warn("Lenient load failed: {}", e.getMessage());
        }

        // Strategy 3: Memory only
        try (InputStream is = new FileInputStream(pdfFile)) {
            PDDocument doc = PDDocument.load(is, (String) null, MemoryUsageSetting.setupMainMemoryOnly());
            log.info("✓ PDF loaded (memory-only): {} pages", doc.getNumberOfPages());
            return doc;
        } catch (IOException e) {
            log.error("All PDF load strategies failed");
            throw new IOException("Cannot open PDF - unsupported or corrupted format", e);
        }
    }



    private int calculateOptimalDpi(File pdfFile, PDDocument document) {
        long fileSizeMb = pdfFile.length() / (1024 * 1024);
        int pageCount = document.getNumberOfPages();

        if (fileSizeMb > HUGE_FILE_MB || pageCount > 15) {
            log.info("Using DPI={} for huge PDF ({}MB, {} pages)", HUGE_FILE_DPI, fileSizeMb, pageCount);
            return HUGE_FILE_DPI;
        }

        if (fileSizeMb > LARGE_FILE_MB || pageCount > 8) {
            log.info("Using DPI={} for large PDF ({}MB, {} pages)", LARGE_FILE_DPI, fileSizeMb, pageCount);
            return LARGE_FILE_DPI;
        }

        log.info("Using DPI={} for standard PDF ({}MB, {} pages)", DEFAULT_DPI, fileSizeMb, pageCount);
        return DEFAULT_DPI;
    }
    private List<BufferedImage> renderPagesSequentially(PDDocument document, int dpi) throws Exception {
        int pageCount = document.getNumberOfPages();
        PDFRenderer renderer = new PDFRenderer(document);
        List<BufferedImage> images = new ArrayList<>();

        log.info("Rendering {} pages sequentially (DPI={})", pageCount, dpi);

        for (int i = 0; i < pageCount; i++) {
            int pageIndex = i;
            try {
                log.debug("Rendering page {}/{}", pageIndex + 1, pageCount);
                BufferedImage img = renderer.renderImageWithDPI(pageIndex, dpi);
                images.add(img);
            } catch (IllegalStateException e) {
                log.error("PDFBox recursion error while rendering page {}: {}", pageIndex + 1, e.getMessage(), e);
                throw new Exception("Unsupported/corrupted PDF structure on page " + (pageIndex + 1) + ": " + e.getMessage(), e);
            } catch (IOException e) {
                log.error("Failed to render page {}", pageIndex + 1, e);
                throw new Exception("Failed to render page " + (pageIndex + 1), e);
            }
        }

        log.info("✓ Rendered {} pages successfully", images.size());
        return images;
    }


    private List<BufferedImage> renderPagesInParallel(PDDocument document, int dpi) throws Exception {
        int pageCount = document.getNumberOfPages();
        PDFRenderer renderer = new PDFRenderer(document);

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
        List<Future<BufferedImage>> futures = new ArrayList<>();

        log.info("Rendering {} pages in parallel (DPI={})", pageCount, dpi);

        for (int i = 0; i < pageCount; i++) {
            final int pageIndex = i;
            Future<BufferedImage> future = executor.submit(() -> {
                try {
                    log.debug("Rendering page {}/{}", pageIndex + 1, pageCount);
                    return renderer.renderImageWithDPI(pageIndex, dpi);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to render page " + (pageIndex + 1), e);
                }
            });
            futures.add(future);
        }

        List<BufferedImage> images = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                BufferedImage img = futures.get(i).get(120, TimeUnit.SECONDS);
                images.add(img);
            } catch (TimeoutException e) {
                executor.shutdownNow();
                throw new Exception("Page rendering timeout on page " + (i + 1));
            }
        }

        executor.shutdown();
        log.info("✓ Rendered {} pages successfully", images.size());
        return images;
    }

    private ExtractionData processPdfDocument(File pdfFile, TokenUsage totalUsage) throws Exception {
        try (PDDocument document = loadPdfRobustly(pdfFile)) {

            int dpi = calculateOptimalDpi(pdfFile, document);
            List<BufferedImage> allImages = renderPagesSequentially(document, dpi);


            List<ExtractionData> batchResults = new ArrayList<>();

            for (int i = 0; i < allImages.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, allImages.size());
                List<BufferedImage> batch = allImages.subList(i, endIndex);

                log.info("Processing batch: pages {}-{}/{}", i + 1, endIndex, allImages.size());

                String batchPrompt = buildEnhancedPrompt(i + 1, endIndex, allImages.size());
                OpenAiResult result = callOpenAiVisionBatch(batch, batchPrompt);

                // accumulate tokens
                totalUsage.setInputTokens(totalUsage.getInputTokens() + result.inputTokens);
                totalUsage.setOutputTokens(totalUsage.getOutputTokens() + result.outputTokens);
                totalUsage.setTotalTokens(
                        totalUsage.getTotalTokens() + result.inputTokens + result.outputTokens
                );

                ExtractionData batchData = parseExtractionResponse(result.content);
                batchResults.add(batchData);

                batch.forEach(BufferedImage::flush);
            }

            ExtractionData mergedData = mergeBatchResults(batchResults);

            allImages.clear();
            System.gc();

            return mergedData;

        } finally {
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
        }
    }


    private String buildEnhancedPrompt(int startPage, int endPage, int totalPages) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert medical bill extraction system. ")
                .append("Extract line items from pages ")
                .append(startPage).append("-").append(endPage)
                .append(" (of ").append(totalPages).append(" total).\n\n")

                .append("CRITICAL RULES:\n")
                .append("1. SCAN ALL PAGES: Extract from EVERY page shown. Do NOT stop at the first table.\n")
                .append("2. SCAN ALL TABLES: If a page has multiple bills/tables, extract ALL of them.\n")
                .append("3. NO SKIPPING: Every row with a charge MUST become a bill_item (unless it's a header/total).\n")
                .append("4. NO DEDUPLICATION: If the same item appears multiple times, keep each occurrence.\n\n")

                .append("OUTPUT STRICTLY IN THIS JSON SHAPE:\n")
                .append("{\n")
                .append("  \"pagewise_line_items\": [\n")
                .append("    {\n")
                .append("      \"page_no\": \"string\",\n")
                .append("      \"page_type\": \"Final Bill\" | \"Bill Detail\" | \"Pharmacy\",\n")
                .append("      \"bill_items\": [\n")
                .append("        {\n")
                .append("          \"item_name\": \"string\",\n")
                .append("          \"item_amount\": float,\n")
                .append("          \"item_rate\": float,\n")
                .append("          \"item_quantity\": float\n")
                .append("        }\n")
                .append("      ]\n")
                .append("    }\n")
                .append("  ]\n")
                .append("}\n\n")
                .append("NOTES:\n")
                .append("- item_name must match the bill text as closely as possible.\n")
                .append("- item_amount is the net amount for that line (after any discount, as printed).\n")
                .append("- item_rate and item_quantity must match the bill.\n")
                .append("Return ONLY valid JSON. No extra text.");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private OpenAiResult callOpenAiVisionBatch(List<BufferedImage> images, String prompt) throws IOException {
        log.debug("Calling GPT-4.1 with {} images", images.size());

        List<Map<String, Object>> contentList = new ArrayList<>();
        contentList.add(Map.of("type", "text", "text", prompt));

        for (BufferedImage image : images) {
            String base64 = convertImageToBase64(image);
            contentList.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of(
                            "url", "data:image/png;base64," + base64,
                            "detail", "high"
                    )
            ));
        }

        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4.1",
                "messages", List.of(Map.of("role", "user", "content", contentList)),
                "max_tokens", 6000,
                "temperature", 0.1
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(openaiApiUrl, requestEntity, Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new IOException("Empty OpenAI response body");
            }

            // content
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // exact token usage from OpenAI
            int inputTokens = 0;
            int outputTokens = 0;

            Object usageObj = body.get("usage");
            if (usageObj instanceof Map) {
                Map<String, Object> usage = (Map<String, Object>) usageObj;
                Object pi = usage.get("prompt_tokens");
                Object co = usage.get("completion_tokens");
                if (pi instanceof Number) inputTokens = ((Number) pi).intValue();
                if (co instanceof Number) outputTokens = ((Number) co).intValue();
            }

            return new OpenAiResult(content, inputTokens, outputTokens);

        } catch (Exception e) {
            log.error("OpenAI API call failed", e);
            throw new IOException("GPT API error: " + e.getMessage(), e);
        }
    }


    private String convertImageToBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private ExtractionData parseExtractionResponse(String jsonResponse) throws IOException {
        String cleaned = jsonResponse.trim();

        if (cleaned.startsWith("```")){
                cleaned = cleaned.substring(7);
    }
    if (cleaned.startsWith("```")) {
        cleaned = cleaned.substring(3);
    }
    if (cleaned.endsWith("```")){
    cleaned = cleaned.substring(0, cleaned.length() - 3);
}
cleaned = cleaned.trim();

    try {
            return objectMapper.readValue(cleaned, ExtractionData.class);
    } catch (Exception e) {
        log.error("JSON parse error. Response: {}", cleaned);
        throw new IOException("Failed to parse GPT response", e);
    }
            }


private ExtractionData mergeBatchResults(List<ExtractionData> batches) {
        ExtractionData merged = new ExtractionData();
        List<PageWiseLineItems> allPages = new ArrayList<>();

        for (ExtractionData batch : batches) {
            if (batch.getPagewiseLineItems() != null) {
                allPages.addAll(batch.getPagewiseLineItems());
            }
        }

        merged.setPagewiseLineItems(allPages);
        return merged;
    }
    private void validateAndReconcile(ExtractionData data) {
        if (data.getPagewiseLineItems() == null || data.getPagewiseLineItems().isEmpty()) {
            throw new IllegalStateException("No line items extracted");
        }

        int totalItems = 0;
        for (PageWiseLineItems page : data.getPagewiseLineItems()) {
            List<BillItem> items = page.getBillItems();
            if (items == null) {
                continue;
            }
            totalItems += items.size();
        }

        data.setTotalItemCount(totalItems);
        log.info("✓ Reconciliation: total_item_count={}", totalItems);
    }

}
