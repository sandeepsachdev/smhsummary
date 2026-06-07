package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BriefingController {
  private static final Logger log = LoggerFactory.getLogger(BriefingController.class);

  private final BriefingService briefingService;

  public BriefingController(BriefingService briefingService) {
    this.briefingService = briefingService;
  }

  @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
  public String index() {
    return briefingService.getCachedOrBuild().webHtml();
  }

  /** Rebuild the briefing (fresh fetch + Claude call) and return the new page. */
  @GetMapping(value = "/refresh", produces = MediaType.TEXT_HTML_VALUE)
  public String refresh() {
    log.info("Manual refresh requested");
    return briefingService.build().webHtml();
  }

  /** Lightweight health/status endpoint. */
  @GetMapping(value = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> health() {
    BriefingService.Briefing b = briefingService.getCached();
    if (b == null) {
      return ResponseEntity.ok(Map.of("status", "starting", "ready", false));
    }
    return ResponseEntity.ok(Map.of(
        "status", "ok",
        "ready", true,
        "articleCount", b.articleCount(),
        "themeCount", b.themeCount(),
        "usedClaude", b.usedClaude(),
        "generatedAt", b.generatedAt().toString()
    ));
  }
}
