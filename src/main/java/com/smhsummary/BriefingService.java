package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the full pipeline: fetch SMH feeds → filter sport →
 * (optionally) enrich via Claude. The cached {@link Briefing} keeps
 * the underlying data (articles, Claude themes, fetch metadata) so we
 * can re-render filtered slices for the {@code ?since=} query without
 * re-calling Claude.
 */
@Service
public class BriefingService {
  private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

  private final FeedService feedService;
  private final ClaudeService claudeService;
  private final BriefingRenderer renderer;
  private final AtomicReference<Briefing> cache = new AtomicReference<>();

  public BriefingService(FeedService feedService, ClaudeService claudeService, BriefingRenderer renderer) {
    this.feedService = feedService;
    this.claudeService = claudeService;
    this.renderer = renderer;
  }

  /**
   * Cached briefing data. The web HTML is rendered on demand per
   * {@code ?since=} value; the email HTML is rendered once at build
   * time because that's what's sent on startup.
   */
  public record Briefing(
      List<Article> articles,
      ClaudeService.BriefingResponse claude,
      FeedService.FetchResult fetch,
      String emailHtml,
      String subject,
      Instant generatedAt
  ) {
    public int articleCount() { return articles == null ? 0 : articles.size(); }
    public int themeCount() { return claude == null || claude.themes() == null ? 0 : claude.themes().size(); }
    public boolean usedClaude() { return claude != null; }
  }

  /** Run the full pipeline, cache the result, and return it. */
  public synchronized Briefing build() {
    FeedService.FetchResult fetch = feedService.fetchAll();
    if (fetch.articles().isEmpty()) {
      throw new IllegalStateException("Feed fetch returned 0 non-sport articles");
    }

    List<Article> articles = new ArrayList<>(fetch.articles());
    ClaudeService.BriefingResponse claude = null;

    if (claudeService.isEnabled()) {
      try {
        claude = claudeService.summariseAndGroup(articles);
        for (int i = 0; i < articles.size() && i < claude.summaries().size(); i++) {
          articles.set(i, articles.get(i).withSummary(claude.summaries().get(i)));
        }
      } catch (Exception e) {
        log.error("Claude enrichment failed — falling back to keyword grouping: {}", e.getMessage(), e);
        claude = null;
      }
    }

    String subject = renderer.subject(articles, fetch);
    String emailHtml = renderer.renderEmail(articles, claude, fetch);

    Briefing b = new Briefing(articles, claude, fetch, emailHtml, subject, Instant.now());
    cache.set(b);
    log.info("Briefing built — {} articles · {} themes · claude={} · subject=\"{}\"",
        b.articleCount(), b.themeCount(), b.usedClaude(), b.subject());
    return b;
  }

  public Briefing getCachedOrBuild() {
    Briefing b = cache.get();
    return b != null ? b : build();
  }

  public Briefing getCached() {
    return cache.get();
  }

  /**
   * Render the web HTML for the given {@code since} cutoff (nullable).
   * When {@code since != null}, articles published on or before that
   * instant are removed and Claude's theme {@code article_indices} are
   * remapped onto the trimmed article list.
   */
  public String renderWeb(Instant since) {
    Briefing b = getCachedOrBuild();
    if (since == null) {
      return renderer.renderPage(b.articles(), b.claude(), b.fetch(), null, b.articleCount());
    }

    List<Article> filtered = new ArrayList<>();
    for (Article a : b.articles()) {
      if (a.pubDate() != null && a.pubDate().isAfter(since)) {
        filtered.add(a);
      }
    }
    ClaudeService.BriefingResponse remapped = remapClaudeForFilter(b.claude(), b.articles(), filtered);
    return renderer.renderPage(filtered, remapped, b.fetch(), since, b.articleCount());
  }

  /**
   * Rewrite Claude's themes so their {@code article_indices} point into
   * {@code filteredArticles} instead of {@code originalArticles}. Themes
   * that lose all their articles are dropped. Article summaries are
   * already attached to the {@link Article} records, so the {@code
   * summaries} array on the response doesn't need remapping for
   * rendering.
   */
  private ClaudeService.BriefingResponse remapClaudeForFilter(
      ClaudeService.BriefingResponse original,
      List<Article> originalArticles,
      List<Article> filteredArticles
  ) {
    if (original == null) return null;

    // Identity key: article link (unique per article in the cached set).
    Map<String, Integer> linkToNewIndex = new HashMap<>();
    for (int i = 0; i < filteredArticles.size(); i++) {
      linkToNewIndex.put(filteredArticles.get(i).link(), i);
    }

    List<ClaudeService.ThemeGroup> newThemes = new ArrayList<>();
    if (original.themes() != null) {
      for (ClaudeService.ThemeGroup t : original.themes()) {
        if (t == null || t.article_indices() == null) continue;
        List<Integer> remapped = new ArrayList<>();
        for (Integer oldIdx : t.article_indices()) {
          if (oldIdx == null || oldIdx < 0 || oldIdx >= originalArticles.size()) continue;
          Article a = originalArticles.get(oldIdx);
          Integer newIdx = linkToNewIndex.get(a.link());
          if (newIdx != null) remapped.add(newIdx);
        }
        if (!remapped.isEmpty()) {
          newThemes.add(new ClaudeService.ThemeGroup(t.name(), t.emoji(), t.reasoning(), remapped));
        }
      }
    }

    return new ClaudeService.BriefingResponse(original.summaries(), newThemes);
  }
}
