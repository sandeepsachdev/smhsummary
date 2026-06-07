package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the full pipeline: fetch SMH feeds → filter sport →
 * (optionally) enrich via Claude → render web + email HTML.
 * Caches the most recent build so the web endpoint serves instantly.
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

  public record Briefing(
      String webHtml,
      String emailHtml,
      String subject,
      int articleCount,
      int themeCount,
      boolean usedClaude,
      Instant generatedAt
  ) {}

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
        // Attach Claude's summaries onto the article records (index-aligned).
        for (int i = 0; i < articles.size() && i < claude.summaries().size(); i++) {
          articles.set(i, articles.get(i).withSummary(claude.summaries().get(i)));
        }
      } catch (Exception e) {
        log.error("Claude enrichment failed — falling back to keyword grouping: {}", e.getMessage(), e);
        claude = null;
      }
    }

    String subject = renderer.subject(articles, fetch);
    String webHtml = renderer.renderPage(articles, claude, fetch);
    String emailHtml = renderer.renderEmail(articles, claude, fetch);

    int themeCount = claude != null ? claude.themes().size() : -1;
    Briefing b = new Briefing(webHtml, emailHtml, subject, articles.size(),
        themeCount, claude != null, Instant.now());
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
}
