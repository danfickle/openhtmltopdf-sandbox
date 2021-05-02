package com.openhtmltopdf.sandbox;
import static spark.Spark.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.servlet.ServletOutputStream;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.bidi.support.ICUBreakers;
import com.openhtmltopdf.bidi.support.ICUTransformers;
import com.openhtmltopdf.extend.FSCacheEx;
import com.openhtmltopdf.extend.FSCacheValue;
import com.openhtmltopdf.extend.impl.FSDefaultCacheStore;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.TextDirection;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder.CacheStore;
import com.openhtmltopdf.util.Diagnostic;

import spark.ModelAndView;
import spark.template.thymeleaf.ThymeleafTemplateEngine;

public class App
{
    private static final Map<String, String> EXAMPLES = loadExamples();
    
    private static void loadExample(Path p, Map<String, String> map) {
        byte[] example;
        try {
            example = Files.readAllBytes(p);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        String exampleStr = new String(example, StandardCharsets.UTF_8);
        String filename = p.getFileName().toString();
        map.put(filename, exampleStr);
    }
    
    private static Map<String, String> loadExamples() {
        Map<String, String> map = new HashMap<>();
        try {
            Files.list(Paths.get(System.getProperty("user.home"), "Documents", "sandbox-examples")).forEach(p -> loadExample(p, map));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }
    
    private static PdfRendererBuilder standardBuilder(String html, FSCacheEx<String, FSCacheValue> cache) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        
        builder.withHtmlContent(html, null);
        
        builder.useUriResolver((base, uri) -> {
            return null;
        });
        
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
        builder.defaultTextDirection(TextDirection.LTR);
    
        builder.useUnicodeLineBreaker(new ICUBreakers.ICULineBreaker(Locale.US));
        builder.useUnicodeCharacterBreaker(new ICUBreakers.ICUCharacterBreaker(Locale.US));
        builder.useUnicodeToLowerTransformer(new ICUTransformers.ICUToLowerTransformer(Locale.US));
        builder.useUnicodeToUpperTransformer(new ICUTransformers.ICUToUpperTransformer(Locale.US));
        builder.useUnicodeToTitleTransformer(new ICUTransformers.ICUToTitleTransformer(Locale.US));
        
        builder.useCacheStore(CacheStore.PDF_FONT_METRICS, cache);

        builder.useFont(() -> App.class.getResourceAsStream("/fonts/JustAnotherHand.ttf"), "handwriting");
        builder.useFont(() -> App.class.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf"), "arabic");
        builder.useFont(() -> App.class.getResourceAsStream("/fonts/DejaVuSans.ttf"), "deja-sans");
        builder.useFont(() -> App.class.getResourceAsStream("/fonts/NotoSansCJKtc-Regular.ttf"), "cjk");

        builder.useFastMode();
        
        return builder;
    }
        
    public static void main(String[] args) 
    {
    	ThymeleafTemplateEngine templateEngine = new ThymeleafTemplateEngine();
    	FSCacheEx<String, FSCacheValue> cache = new FSDefaultCacheStore();
    	
    	port(8080);
    	
        get("/", (req, res) -> {
        	Map<String, Object> vars = new HashMap<>();
        	String file = req.queryParamOrDefault("file", "hello-world.htm");
        	
        	vars.put("examples", EXAMPLES);
        	vars.put("example", EXAMPLES.get(file));
        	vars.put("file", file);
        	
        	return new ModelAndView(vars, "start");
        }, templateEngine);
        
        post("/post-logs", (req, res) -> {
            res.header("Content-Type", "text/plain");
            res.header("X-Content-Type-Options", "nosniff");

            List<Diagnostic> logs = new ArrayList<>();

            try (ByteArrayOutputStream os = new ByteArrayOutputStream(16000)) {
                PdfRendererBuilder builder = standardBuilder(req.queryParams("upload-area"), cache);

                builder.toStream(os);
                builder.withDiagnosticConsumer(logs::add);
                builder.run();
            }
            
            return logs.stream()
                    .filter(diag -> diag.getLevel() == Level.WARNING || diag.getLevel() == Level.SEVERE || diag.getLevel() == Level.INFO)
                    .map(diag -> diag.getFormattedMessage())
                    .collect(Collectors.joining("\r\n"));
        });
        
        post("/post-pdf", (req, res) -> {
        	res.header("Content-Type", "application/pdf");
        	
        	try(ServletOutputStream os = res.raw().getOutputStream()) 
        	{
                PdfRendererBuilder builder = standardBuilder(req.queryParams("upload-area"), cache);
                builder.toStream(os);
        	    builder.run();
        	}
            
        	return null;
        });
    }
}

