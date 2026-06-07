package com.smhsummary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClaudeService {
  private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
  private static final String MODEL = "claude-haiku-4-5";

  private final AnthropicClient client;

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

  // Snake_case field name so the auto-derived JSON schema matches the
  // shape Claude naturally returns for the briefing — saves us depending
  // on @JsonProperty being honoured by the SDK's schema generator.
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
    var response = client.messages().create(params);

    return response.content().stream()
        .flatMap(cb -> cb.text().stream())
        .findFirst()
        .map(typed -> typed.text())
        .orElseThrow(() -> new RuntimeException("Claude returned no parsed structured output"));
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
