package com.echomind.memory.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 知识库文件文本提取器。
 *
 * <p>只支持 txt 和 pdf。PDF 会先尝试抽取内嵌文本；如果是扫描版 PDF，
 * 内嵌文本通常为空，此时会把页面渲染为图片并交给 Tesseract OCR 识别。</p>
 */
@Slf4j
public class AgentKnowledgeTextExtractor {

    private static final int OCR_PAGE_TIMEOUT_SECONDS = 90;
    private static final int MAX_ERROR_CHARS = 300;

    private final boolean ocrEnabled;
    private final String ocrLanguage;
    private final int ocrDpi;
    private final int ocrMinTextChars;
    private final int ocrMaxPages;
    private final String tesseractCommand;

    public AgentKnowledgeTextExtractor() {
        this(true, "chi_sim+eng", 200, 80, 20, "tesseract");
    }

    public AgentKnowledgeTextExtractor(boolean ocrEnabled,
                                       String ocrLanguage,
                                       int ocrDpi,
                                       int ocrMinTextChars,
                                       int ocrMaxPages,
                                       String tesseractCommand) {
        this.ocrEnabled = ocrEnabled;
        this.ocrLanguage = blankToDefault(ocrLanguage, "chi_sim+eng");
        this.ocrDpi = Math.max(120, Math.min(300, ocrDpi));
        this.ocrMinTextChars = Math.max(20, ocrMinTextChars);
        this.ocrMaxPages = Math.max(1, ocrMaxPages);
        this.tesseractCommand = blankToDefault(tesseractCommand, "tesseract");
    }

    /** 根据文件名和内容提取纯文本。 */
    public ExtractedText extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        String type = detectType(fileName);
        try {
            if ("pdf".equals(type)) {
                return new ExtractedText(type, extractPdf(bytes));
            }
            if ("txt".equals(type)) {
                return new ExtractedText(type, new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("文件解析失败：" + e.getMessage());
        }
        throw new IllegalArgumentException("只支持上传 txt 或 pdf 文件");
    }

    private String detectType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        return "";
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            String embeddedText = extractEmbeddedPdfText(document);
            if (hasEnoughText(embeddedText) || !ocrEnabled) {
                return embeddedText;
            }
            try {
                String ocrText = extractScannedPdfText(document);
                if (!ocrText.isBlank()) {
                    return joinText(embeddedText, ocrText);
                }
            } catch (IOException e) {
                if (isBlank(embeddedText)) {
                    throw e;
                }
                log.warn("PDF OCR 失败，已保留 PDF 内嵌文本：{}", e.getMessage());
            }
            return embeddedText;
        }
    }

    private String extractEmbeddedPdfText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }

    /**
     * 扫描版 PDF 没有可直接读取的文字，这里逐页渲染成 PNG，再调用系统里的 tesseract 命令。
     *
     * <p>OCR 是 CPU 密集型操作，所以限制最大页数；知识库上传失败时会返回明确错误，
     * 避免静默入库一堆空切片。</p>
     */
    private String extractScannedPdfText(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        int pages = Math.min(document.getNumberOfPages(), ocrMaxPages);
        StringBuilder result = new StringBuilder();
        for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
            Path imagePath = Files.createTempFile("echomind-knowledge-ocr-", ".png");
            try {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, ocrDpi, ImageType.RGB);
                javax.imageio.ImageIO.write(image, "png", imagePath.toFile());
                String pageText = runTesseract(imagePath, pageIndex + 1);
                if (!pageText.isBlank()) {
                    result.append(pageText.strip()).append(System.lineSeparator()).append(System.lineSeparator());
                }
            } finally {
                Files.deleteIfExists(imagePath);
            }
        }
        return result.toString().strip();
    }

    private String runTesseract(Path imagePath, int pageNumber) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
            tesseractCommand,
            imagePath.toAbsolutePath().toString(),
            "stdout",
            "-l",
            ocrLanguage
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            boolean finished = process.waitFor(OCR_PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            stream.transferTo(output);
            String text = output.toString(StandardCharsets.UTF_8);
            if (!finished) {
                throw new IOException("第 " + pageNumber + " 页 OCR 超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("第 " + pageNumber + " 页 OCR 失败：" + truncate(text, MAX_ERROR_CHARS));
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("OCR 进程被中断", e);
        }
    }

    private boolean hasEnoughText(String text) {
        return text != null && text.replaceAll("\\s+", "").length() >= ocrMinTextChars;
    }

    private String joinText(String embeddedText, String ocrText) {
        if (isBlank(embeddedText)) {
            return ocrText;
        }
        if (isBlank(ocrText)) {
            return embeddedText;
        }
        return embeddedText.strip() + System.lineSeparator() + System.lineSeparator() + ocrText.strip();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 文件类型和提取出的纯文本。 */
    public record ExtractedText(String fileType, String text) {}
}
