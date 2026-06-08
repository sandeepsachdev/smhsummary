package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

@RestController
public class BriefingController {
  private static final Logger log = LoggerFactory.getLogger(BriefingController.class);

  private final BriefingService briefingService;

  public BriefingController(BriefingService briefingService) {
    this.briefingService = briefingService;
  }

  /**
   * Renders the briefing. The optional {@code since} query parameter
   * (ISO-8601 instant, e.g. {@code 2026-06-08T01:23:45Z}) restricts the
   * output to articles published strictly after that moment. The
   * browser supplies this value from its {@code localStorage}
   * {@code lastRunTime} via a head script that redirects on load.
   */
  @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
  public String index(@RequestParam Optional<String> since) {
    Instant cutoff = parseSince(since.orElse(null));
    return briefingService.renderWeb(cutoff);
  }

  /**
   * Rebuild the briefing (fresh fetch + Claude call), then 302 to {@code /}
   * so the redirect-on-load script can re-run the since handshake exactly
   * once. Returning HTML here would double-build because the response
   * itself would trigger another redirect into /refresh?since=...
   */
  @GetMapping("/refresh")
  public ResponseEntity<Void> refresh() {
    log.info("Manual refresh requested");
    briefingService.build();
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
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

  private Instant parseSince(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      log.warn("Ignoring invalid ?since param: {}", raw);
      return null;
    }
  }
}
