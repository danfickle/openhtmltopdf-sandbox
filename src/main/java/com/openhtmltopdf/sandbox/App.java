package com.openhtmltopdf.sandbox;
import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

import org.apache.pdfbox.io.IOUtils;

import spark.ModelAndView;
import spark.template.thymeleaf.ThymeleafTemplateEngine;

public class App
{
    private enum Sample {
        HELLO_WORLD,
        INVOICE,
        CJK,
        PAGE_FEATURES;
    }

    private enum FontSample {
        HANDWRITING("JustAnotherHand.ttf", "handwriting"),
        ARABIC("NotoNaskhArabic-Regular.ttf", "arabic"),
        DEJA_SANS("DejaVuSans.ttf", "deja-sans"),
        CJK("NotoSansCJKtc-Regular.ttf", "cjk");

        private String resFile;
        private String fontFamily;

        FontSample(String resFile, String fontFamily) {
          this.resFile = resFile;
          this.fontFamily = fontFamily;
        }
    }

    private static final Map<String, String> EXAMPLES = loadExamples();

    private static void loadExample(Sample sample, Map<String, String> map) {
        String resFile = sample.name().toLowerCase(Locale.US).replace('_', '-') + ".htm";

        try (InputStream is = App.class.getResourceAsStream("/samples/" + resFile)) {
            String resContents = new String(IOUtils.toByteArray(is), StandardCharsets.UTF_8);
            System.out.format("Loaded resource file '%s' with %d chars.", resFile, resContents.length());
            map.put(resFile, resContents);
        } catch (IOException e) {
            System.err.format("Unable to read resource '%s'.", resFile);  
            e.printStackTrace();
        }
    }
    
    private static Map<String, String> loadExamples() {
        Map<String, String> map = new ConcurrentHashMap<>();

        Stream.of(Sample.values())
          .forEach(sample -> loadExample(sample, map));

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

        Stream.of(FontSample.values())
          .forEach(font -> builder.useFont(() -> App.class.getResourceAsStream("/fonts/" + font.resFile), font.fontFamily));  

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

