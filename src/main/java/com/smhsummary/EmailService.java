package com.smhsummary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the briefing as an HTML email via the Resend API.
 * No-op when RESEND_API_KEY or EMAIL_TO is unset, so the app still
 * comes up cleanly during local testing.
 */
@Service
public class EmailService {
  private static final Logger log = LoggerFactory.getLogger(EmailService.class);
  private static final String RESEND_URL = "https://api.resend.com/emails";

  private final String apiKey;
  private final String from;
  private final List<String> to;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  public EmailService(
      @Value("${resend.api-key:}") String apiKey,
      @Value("${resend.from:Briefing <onboarding@resend.dev>}") String from,
      @Value("${resend.to:}") String to
  ) {
    this.apiKey = apiKey == null ? "" : apiKey;
    this.from = from;
    this.to = (to == null || to.isBlank())
        ? List.of()
        : Arrays.stream(to.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
  }

  public boolean isEnabled() {
    return !apiKey.isBlank() && !to.isEmpty();
  }

  public void send(String subject, String html) {
    if (!isEnabled()) {
      log.info("Email skipped — RESEND_API_KEY or EMAIL_TO not configured");
      return;
    }
    try {
      // LinkedHashMap so the JSON keys come out in a predictable order.
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("from", from);
      body.put("to", to);
      body.put("subject", subject);
      body.put("html", html);
      String json = mapper.writeValueAsString(body);

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(RESEND_URL))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build();

      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        log.error("Resend rejected the request ({}): {}", res.statusCode(), res.body());
        return;
      }
      log.info("Email sent to {} ({}) — Resend response: {}", to, subject, res.body());
    } catch (Exception e) {
      log.error("Email send failed: {}", e.getMessage(), e);
    }
  }
}
