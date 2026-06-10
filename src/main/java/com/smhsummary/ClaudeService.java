package com.smhsummary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ClaudeService {
  private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
  private static final String MODEL = "claude-haiku-4-5";

  // Haiku 4.5 pricing (USD per 1M tokens). Update when Anthropic changes prices.
  private static final double INPUT_USD_PER_M = 1.0;
  private static final double OUTPUT_USD_PER_M = 5.0;

  private final AnthropicClient client;

  // Cumulative usage since process start. Resets on JVM restart.
  private final AtomicLong cumulativeInputTokens = new AtomicLong();
  private final AtomicLong cumulativeOutputTokens = new AtomicLong();
  private volatile UsageStats lastUsage;

  public ClaudeService(@Value("${anthropic.api-key:}") String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
      log.info("Claude client initialized ({})", MODEL);
    } else {
      this.client = null;
      log.warn("ANTHROPIC_API_KEY not set — Claude enrichment disabled, falling back to keyword grouping");
    }
  }

  public boolean isEnabled() {
    return client != null;
  }

  // Snake_case so the auto-derived JSON schema matches what Claude returns.
  public record ThemeGroup(
      String name,
      String emoji,
      String reasoning,
      List<Integer> article_indices
  ) {}

  public record BriefingResponse(
      List<String> summaries,
      List<ThemeGroup> themes
  ) {}

  /**
   * Token + cost snapshot from the most recent Claude call plus the
   * cumulative totals since process start. Rate-limit fields come from
   * Anthropic's response headers; they're null when not available.
   */
  public record UsageStats(
      long lastInputTokens,
      long lastOutputTokens,
      double lastCostUsd,
      long cumulativeInputTokens,
      long cumulativeOutputTokens,
      double cumulativeCostUsd,
      Long rateLimitTokensRemaining,
      Long rateLimitTokensLimit,
      Instant rateLimitTokensReset,
      long claudeDurationMs
  ) {}

  public UsageStats getLastUsage() { return lastUsage; }

  public BriefingResponse summariseAndGroup(List<Article> articles) {
    if (client == null) {
      throw new IllegalStateException("Claude client not configured");
    }

    String prompt = buildPrompt(articles);

    var params = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(16000L)
        .outputConfig(BriefingResponse.class)
        .addUserMessage(prompt)
        .build();

    log.info("Calling Claude {} for {} articles", MODEL, articles.size());

    // Use the typed create() — the response object carries the parsed
    // BriefingResponse via .content().stream() and also exposes .usage().
    long startNanos = System.nanoTime();
    var response = client.messages().create(params);
    long claudeDurationMs = (System.nanoTime() - startNanos) / 1_000_000L;

    BriefingResponse parsed = response.content().stream()
        .flatMap(cb -> cb.text().stream())
        .findFirst()
        .map(typed -> typed.text())
        .orElseThrow(() -> new RuntimeException("Claude returned no parsed structured output"));

    long inputTokens = readLong(response, "usage", "inputTokens");
    long outputTokens = readLong(response, "usage", "outputTokens");

    long cumIn = cumulativeInputTokens.addAndGet(inputTokens);
    long cumOut = cumulativeOutputTokens.addAndGet(outputTokens);
    double callCost = costUsd(inputTokens, outputTokens);
    double cumCost = costUsd(cumIn, cumOut);

    // Rate-limit headers come from the underlying HTTP response. The
    // structured-outputs return type may or may not expose them
    // directly — try a couple of accessors via reflection and fall
    // back to nulls if the SDK doesn't surface them on this code path.
    Long limitRemaining = parseLong(headerFromResponse(response, "anthropic-ratelimit-tokens-remaining"));
    Long limitTotal = parseLong(headerFromResponse(response, "anthropic-ratelimit-tokens-limit"));
    Instant limitReset = parseInstantOrNull(headerFromResponse(response, "anthropic-ratelimit-tokens-reset"));

    this.lastUsage = new UsageStats(
        inputTokens, outputTokens, callCost,
        cumIn, cumOut, cumCost,
        limitRemaining, limitTotal, limitReset,
        claudeDurationMs);

    log.info("Claude usage: this call in={} out={} cost=${} · cumulative in={} out={} cost=${} · limit remaining={}/{} · {}ms",
        inputTokens, outputTokens, String.format("%.5f", callCost),
        cumIn, cumOut, String.format("%.5f", cumCost),
        limitRemaining, limitTotal,
        claudeDurationMs);

    return parsed;
  }

  // --- Defensive accessors --------------------------------------------------
  // The Anthropic Java SDK's structured-outputs return type is not the plain
  // Message class, so its exact method surface can drift between SDK
  // versions. Walk it reflectively so a missing accessor degrades to
  // "unknown" instead of a hard compile failure. If/when a stable typed API
  // for headers + usage on structured responses lands, replace these.

  private static long readLong(Object root, String... path) {
    Object cur = root;
    for (String name : path) {
      cur = invokeNoArgs(cur, name);
      if (cur == null) return 0L;
    }
    if (cur instanceof Number n) return n.longValue();
    if (cur instanceof Optional<?> o && o.isPresent() && o.get() instanceof Number n) return n.longValue();
    return 0L;
  }

  private static String headerFromResponse(Object response, String headerName) {
    // Try common patterns: response._headers(), response.headers(), or
    // response.metadata().get(...). All are best-effort.
    for (String accessor : new String[]{"headers", "_headers", "responseHeaders"}) {
      Object headers = invokeNoArgs(response, accessor);
      if (headers == null) continue;
      String value = readHeader(headers, headerName);
      if (value != null) return value;
    }
    return null;
  }

  private static String readHeader(Object headers, String name) {
    // values(String) -> List<String>
    Object listResult = invokeOneArg(headers, "values", name);
    if (listResult instanceof List<?> list && !list.isEmpty()) {
      return String.valueOf(list.get(0));
    }
    // firstValue(String) -> Optional<String>
    Object optResult = invokeOneArg(headers, "firstValue", name);
    if (optResult instanceof Optional<?> opt && opt.isPresent()) {
      return String.valueOf(opt.get());
    }
    // get(String) -> String (less common)
    Object directResult = invokeOneArg(headers, "get", name);
    if (directResult != null) return String.valueOf(directResult);
    return null;
  }

  private static Object invokeNoArgs(Object target, String methodName) {
    if (target == null) return null;
    try {
      Method m = target.getClass().getMethod(methodName);
      return m.invoke(target);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Object invokeOneArg(Object target, String methodName, Object arg) {
    if (target == null) return null;
    try {
      Method m = target.getClass().getMethod(methodName, String.class);
      return m.invoke(target, arg);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private Long parseLong(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
  }

  private Instant parseInstantOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Instant.parse(s.trim()); } catch (DateTimeParseException e) { return null; }
  }

  private static double costUsd(long inputTokens, long outputTokens) {
    return (inputTokens / 1_000_000.0) * INPUT_USD_PER_M
        + (outputTokens / 1_000_000.0) * OUTPUT_USD_PER_M;
  }

  private String buildPrompt(List<Article> articles) {
    StringBuilder sb = new StringBuilder(8 * 1024);
    sb.append("You are organizing a Sydney Morning Herald briefing — ")
        .append(articles.size())
        .append(" articles from the current SMH feed. Sport articles have already been excluded; do not produce a sport theme.\n\n");

    sb.append("For each article, write a single concise sentence summary (max 22 words, factual, no marketing tone, no hedging like \"reportedly\"). ")
        .append("Then group them into 4–9 coherent themes specific to the news in this feed — themes should reflect what's actually being reported, ")
        .append("not generic categories like \"Politics\". Each theme gets a short name (2–4 words), one fitting emoji, and a brief reasoning note ")
        .append("(one sentence, 15–30 words) explaining what ties these articles together — the narrative thread or shared subject, not a recap.\n\n");

    sb.append("Constraints:\n")
        .append("- Every article appears in exactly one theme\n")
        .append("- article_indices are 0-indexed into the input list below\n")
        .append("- summaries array length must equal ").append(articles.size()).append(", in input order\n")
        .append("- Within a theme, order articles by importance (lead story first)\n")
        .append("- Order themes by significance (biggest story first)\n\n");

    sb.append("Articles:\n");
    for (int i = 0; i < articles.size(); i++) {
      Article a = articles.get(i);
      sb.append('[').append(i).append("] ").append(a.title()).append('\n');
      if (a.description() != null && !a.description().isBlank()) {
        sb.append(a.description()).append('\n');
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
