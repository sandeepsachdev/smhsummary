package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders the briefing into two HTML variants:
 *   - Web page: collapsible {@code <details>} sections, full styling.
 *   - Email: simpler structure, all expanded, email-safe CSS.
 *
 * When Claude isn't available, falls back to a keyword classifier so
 * the page still groups articles sensibly.
 */
@Component
public class BriefingRenderer {
  private static final Logger log = LoggerFactory.getLogger(BriefingRenderer.class);

  private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
  private static final DateTimeFormatter DATE_LABEL =
      DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH).withZone(SYDNEY);
  private static final DateTimeFormatter TIME_LABEL =
      DateTimeFormatter.ofPattern("d MMM · hh:mm a", Locale.ENGLISH).withZone(SYDNEY);
  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(SYDNEY);
  private static final DateTimeFormatter LAST_BUILT =
      DateTimeFormatter.ofPattern("EEE d MMM, hh:mm a", Locale.ENGLISH).withZone(SYDNEY);

  private final String webCss;
  private final String emailCss;

  public BriefingRenderer() {
    this.webCss = loadResource("/templates/web.css");
    this.emailCss = loadResource("/templates/email.css");
  }

  /**
   * Head script that drives the since-filter handshake:
   *
   *   - If the URL has no {@code ?since=} param AND the browser has a
   *     stored {@code lastRunTime}, immediately {@code location.replace}
   *     to {@code /?since=<iso>}. The redirect happens synchronously in
   *     {@code <head>} before any paint, so no unfiltered flash.
   *   - Otherwise (filtered page just arrived, or first visit), save
   *     the current time as the new {@code lastRunTime}.
   *
   * Also exposes {@code window.__showAll} for the banner's "show all"
   * link: clears {@code lastRunTime} and navigates to {@code /} so the
   * server returns the unfiltered briefing.
   */
  private static final String SINCE_REDIRECT_SCRIPT = """
      <script>
      (function() {
        var KEY = 'lastRunTime';
        var url = new URL(location.href);
        var hasSince = url.searchParams.has('since');
        var stored = localStorage.getItem(KEY);

        window.__showAll = function(e) {
          if (e) e.preventDefault();
          localStorage.removeItem(KEY);
          location.replace(location.pathname);
          return false;
        };

        if (!hasSince && stored) {
          url.searchParams.set('since', stored);
          location.replace(url.toString());
          return;
        }

        localStorage.setItem(KEY, new Date().toISOString());
      })();
      </script>
      """;

  // --- Public API ----------------------------------------------------------

  public String subject(List<Article> articles, FeedService.FetchResult fetch) {
    return "SMH Briefing — " + DATE_LABEL.format(Instant.now())
        + " (" + articles.size() + " articles)";
  }

  public String renderPage(List<Article> articles, ClaudeService.BriefingResponse claude,
                           FeedService.FetchResult fetch, Instant since, int totalAvailable,
                           ClaudeService.UsageStats usage) {
    List<ThemeView> groups = groupArticles(articles, claude);
    Instant now = Instant.now();

    StringBuilder sb = new StringBuilder(64 * 1024);
    sb.append("<!DOCTYPE html>\n")
        .append("<html lang=\"en\">\n<head>\n")
        .append("<meta charset=\"UTF-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        .append("<title>SMH — Today's Briefing</title>\n")
        // Inline IIFE runs synchronously before paint so the redirect to
        // /?since=<iso> happens without any flash of unfiltered content.
        .append(SINCE_REDIRECT_SCRIPT)
        .append("<link href=\"https://fonts.googleapis.com/css2?family=Playfair+Display:ital,wght@0,700;0,900;1,400&family=Source+Serif+4:ital,opsz,wght@0,8..60,300;0,8..60,400;1,8..60,300&family=DM+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">\n")
        .append("<style>\n").append(webCss).append("\n</style>\n")
        .append("</head>\n<body>\n");

    // Masthead
    sb.append("<header class=\"masthead\">\n  <div>\n")
        .append("    <div class=\"masthead-title\">SMH Briefing</div>\n")
        .append("    <div class=\"masthead-sub\">Sydney Morning Herald — Full Feed, No Sport</div>\n")
        .append("  </div>\n")
        .append("  <div class=\"masthead-date\">").append(escape(DATE_LABEL.format(now)))
        .append("<br>Sydney, Australia</div>\n")
        .append("</header>\n");

    // Controls (refresh link, build metadata)
    sb.append("<div class=\"controls\">\n")
        .append("  <a class=\"btn\" href=\"/refresh\">↺ Refresh</a>\n")
        .append("  <span class=\"cutoff-info\">")
        .append(fetch.feedsSucceeded()).append('/').append(fetch.feedsTotal()).append(" feeds · ")
        .append(fetch.rawItems()).append(" raw · ")
        .append(articles.size()).append(" kept · ")
        .append(fetch.sportFiltered()).append(" sport filtered");
    if (claude == null) sb.append(" · keyword grouping (no Claude)");
    else sb.append(" · AI-grouped");
    sb.append("</span>\n</div>\n");

    // Main
    sb.append("<main class=\"main\">\n");

    // Stats
    sb.append("<div class=\"stats-bar\">\n")
        .append("  <div class=\"stat\"><span class=\"stat-num\">").append(articles.size())
        .append("</span><span class=\"stat-label\">Articles</span></div>\n")
        .append("  <div class=\"stat-divider\"></div>\n")
        .append("  <div class=\"stat\"><span class=\"stat-num\">").append(groups.size())
        .append("</span><span class=\"stat-label\">Themes</span></div>\n")
        .append("  <div class=\"stat-divider\"></div>\n")
        .append("  <div class=\"stat\"><span class=\"stat-num\">")
        .append(escape(LAST_BUILT.format(now))).append("</span>")
        .append("<span class=\"stat-label\">Last built (AEST)</span></div>\n")
        .append("</div>\n");

    // Usage bar — per-call, cumulative, and rate-limit info from Anthropic.
    if (usage != null) {
      sb.append("<div class=\"usage-bar\">\n");
      sb.append(usageCell("This call",
          formatTokens(usage.lastInputTokens()) + " in · " +
          formatTokens(usage.lastOutputTokens()) + " out · " +
          formatCost(usage.lastCostUsd())));
      sb.append(usageCell("Cumulative (since startup)",
          formatTokens(usage.cumulativeInputTokens()) + " in · " +
          formatTokens(usage.cumulativeOutputTokens()) + " out · " +
          formatCost(usage.cumulativeCostUsd())));
      sb.append(usageCell("Rate-limit (tokens this window)", formatRateLimit(usage)));
      sb.append("</div>\n");
    }

    // Since-banner (server-rendered when ?since= was in the URL)
    if (since != null) {
      int hidden = Math.max(0, totalAvailable - articles.size());
      String label = formatSinceLabel(since);
      sb.append("<div class=\"since-banner\">");
      if (articles.isEmpty()) {
        sb.append("<strong>Caught up.</strong> No new articles since ")
            .append(escape(label))
            .append(". <a href=\"/\" onclick=\"return __showAll(event)\">show all ")
            .append(totalAvailable).append("</a>");
      } else {
        sb.append("<strong>").append(articles.size()).append(" new article")
            .append(articles.size() == 1 ? "" : "s").append("</strong> since ")
            .append(escape(label))
            .append(" &middot; ").append(hidden).append(" older hidden &middot; ")
            .append("<a href=\"/\" onclick=\"return __showAll(event)\">show all</a>");
      }
      sb.append("</div>\n");
    }

    // Theme groups (collapsed by default)
    for (int gi = 0; gi < groups.size(); gi++) {
      ThemeView t = groups.get(gi);
      sb.append("<details class=\"theme-group\" style=\"animation-delay:")
          .append(String.format(Locale.ROOT, "%.2f", gi * 0.06)).append("s\">\n")
          .append("  <summary class=\"theme-header\">\n")
          .append("    <span style=\"font-size:18px\">").append(escape(t.emoji())).append("</span>\n")
          .append("    <span class=\"theme-tag\">").append(escape(t.name())).append("</span>\n")
          .append("    <span class=\"theme-count\">").append(t.articles().size())
          .append(" article").append(t.articles().size() == 1 ? "" : "s").append("</span>\n")
          .append("    <span class=\"expand-icon\" aria-hidden=\"true\">▾</span>\n")
          .append("  </summary>\n");

      if (t.reasoning() != null && !t.reasoning().isBlank()) {
        sb.append("  <div class=\"theme-reasoning\">").append(escape(t.reasoning())).append("</div>\n");
      }

      sb.append("  <div class=\"articles-grid\">\n");
      for (Article a : t.articles()) {
        sb.append("    <a class=\"article-card\" href=\"").append(escapeAttr(a.link()))
            .append("\" target=\"_blank\" rel=\"noopener\">\n")
            .append("      <div class=\"article-body\">\n");
        if (a.category() != null && !a.category().isBlank()) {
          sb.append("        <div class=\"article-category\">").append(escape(a.category()))
              .append("</div>\n");
        }
        sb.append("        <div class=\"article-title\">").append(escape(a.title())).append("</div>\n");
        String summary = pickSummary(a);
        if (!summary.isBlank()) {
          sb.append("        <div class=\"article-summary\">").append(escape(summary)).append("</div>\n");
        }
        sb.append("      </div>\n")
            .append("      <div class=\"article-meta\">\n")
            .append("        <span class=\"article-time\">").append(escape(formatTime(a.pubDate())))
            .append("</span>\n")
            .append("        <span class=\"article-link-icon\">↗</span>\n")
            .append("      </div>\n    </a>\n");
      }
      sb.append("  </div>\n</details>\n");
    }

    sb.append("</main>\n");
    sb.append("<footer class=\"footer\">SMH RSS Reader &nbsp;·&nbsp; Full feed, sport filtered &nbsp;·&nbsp; Data from smh.com.au</footer>\n");
    sb.append("</body>\n</html>\n");
    return sb.toString();
  }

  public String renderEmail(List<Article> articles, ClaudeService.BriefingResponse claude,
                            FeedService.FetchResult fetch) {
    List<ThemeView> groups = groupArticles(articles, claude);
    Instant now = Instant.now();

    StringBuilder sb = new StringBuilder(32 * 1024);
    sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        .append("<meta charset=\"UTF-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        .append("<title>SMH Briefing — ").append(escape(ISO_DATE.format(now))).append("</title>\n")
        .append("<style>\n").append(emailCss).append("\n</style>\n")
        .append("</head>\n<body>\n");

    sb.append("<div class=\"email-wrap\">\n");
    sb.append("<div class=\"masthead\">\n")
        .append("  <h1>SMH Briefing</h1>\n")
        .append("  <p class=\"date\">").append(escape(DATE_LABEL.format(now)))
        .append(" · Sydney, Australia</p>\n")
        .append("</div>\n");

    sb.append("<p class=\"stats\">")
        .append("<strong>").append(articles.size()).append("</strong> articles")
        .append("<span class=\"divider\">·</span>")
        .append("<strong>").append(groups.size()).append("</strong> themes")
        .append("<span class=\"divider\">·</span>")
        .append(fetch.feedsSucceeded()).append('/').append(fetch.feedsTotal()).append(" feeds")
        .append("<span class=\"divider\">·</span>")
        .append(fetch.sportFiltered()).append(" sport filtered")
        .append("</p>\n");

    for (ThemeView t : groups) {
      sb.append("<div class=\"theme\">\n");
      sb.append("  <div class=\"theme-head\">")
          .append("<span class=\"emoji\">").append(escape(t.emoji())).append("</span>")
          .append("<span class=\"name\">").append(escape(t.name())).append("</span>")
          .append("<span class=\"count\">").append(t.articles().size())
          .append(" article").append(t.articles().size() == 1 ? "" : "s").append("</span>")
          .append("</div>\n");
      if (t.reasoning() != null && !t.reasoning().isBlank()) {
        sb.append("  <div class=\"reasoning\">")
            .append("<span class=\"label\">Editor's note</span>")
            .append(escape(t.reasoning())).append("</div>\n");
      }
      for (Article a : t.articles()) {
        sb.append("  <div class=\"article\">\n");
        if (a.category() != null && !a.category().isBlank()) {
          sb.append("    <div class=\"category\">").append(escape(a.category())).append("</div>\n");
        }
        sb.append("    <div class=\"headline\"><a href=\"").append(escapeAttr(a.link()))
            .append("\">").append(escape(a.title())).append("</a></div>\n");
        String summary = pickSummary(a);
        if (!summary.isBlank()) {
          sb.append("    <div class=\"summary\">").append(escape(summary)).append("</div>\n");
        }
        sb.append("    <div class=\"meta\">").append(escape(formatTime(a.pubDate()))).append("</div>\n");
        sb.append("  </div>\n");
      }
      sb.append("</div>\n");
    }

    sb.append("<p class=\"footer\">SMH RSS Reader · Full feed, sport filtered · ")
        .append(escape(LAST_BUILT.format(now))).append(" · Data from smh.com.au</p>\n");
    sb.append("</div>\n</body>\n</html>\n");
    return sb.toString();
  }

  // --- Grouping ------------------------------------------------------------

  private record ThemeView(String name, String emoji, String reasoning, List<Article> articles) {}

  private List<ThemeView> groupArticles(List<Article> articles, ClaudeService.BriefingResponse claude) {
    if (claude != null && claude.themes() != null && !claude.themes().isEmpty()) {
      return groupFromClaude(articles, claude);
    }
    return groupFromKeywords(articles);
  }

  private List<ThemeView> groupFromClaude(List<Article> articles, ClaudeService.BriefingResponse claude) {
    Set<Integer> claimed = new HashSet<>();
    List<ThemeView> out = new ArrayList<>();
    for (ClaudeService.ThemeGroup t : claude.themes()) {
      if (t == null) continue;
      List<Article> bucket = new ArrayList<>();
      if (t.article_indices() != null) {
        for (Integer i : t.article_indices()) {
          if (i == null || i < 0 || i >= articles.size()) continue;
          if (!claimed.add(i)) continue;
          bucket.add(articles.get(i));
        }
      }
      if (bucket.isEmpty()) continue;
      sortByDateDesc(bucket);
      out.add(new ThemeView(
          t.name() == null ? "Untitled" : t.name(),
          t.emoji() == null || t.emoji().isBlank() ? "📰" : t.emoji(),
          t.reasoning(),
          bucket));
    }
    // Sweep up any articles Claude didn't cover into a trailing bucket.
    List<Article> orphans = new ArrayList<>();
    for (int i = 0; i < articles.size(); i++) {
      if (!claimed.contains(i)) orphans.add(articles.get(i));
    }
    if (!orphans.isEmpty()) {
      sortByDateDesc(orphans);
      out.add(new ThemeView("Other", "📄", null, orphans));
    }
    return out;
  }

  /** Newest article first within a theme. Articles with no pubDate sink to the bottom. */
  private static void sortByDateDesc(List<Article> bucket) {
    bucket.sort(Comparator.comparing(
        Article::pubDate,
        Comparator.nullsLast(Comparator.reverseOrder())));
  }

  // --- Fallback keyword classifier (mirrors smh.html THEMES) -------------

  private record KeywordTheme(String name, String emoji, List<String> keywords) {}

  private static final List<KeywordTheme> KEYWORD_THEMES = List.of(
      new KeywordTheme("Politics & Government", "🏛️",
          List.of("government", "minister", "parliament", "albanese", "premier", "senate",
              "federal", "election", "policy", "political", "labor", "liberal", "coalition",
              "budget", "aec", "legislation")),
      new KeywordTheme("Crime & Justice", "⚖️",
          List.of("police", "court", "murder", "charged", "arrested", "guilty", "verdict",
              "trial", "crime", "attack", "assault", "shooting", "stabbing", "robbery",
              "fraud", "bail", "sentence")),
      new KeywordTheme("Business & Economy", "📈",
          List.of("shares", "asx", "market", "economy", "economic", "bank", "interest rate",
              "inflation", "gdp", "profit", "revenue", "ceo", "company", "trade", "stock",
              "housing", "mortgage", "real estate")),
      new KeywordTheme("World News", "🌏",
          List.of("ukraine", "russia", "china", "us", "united states", "israel", "gaza",
              "middle east", "europe", "nato", "trump", "biden", "war", "conflict",
              "international")),
      new KeywordTheme("Technology & AI", "💻",
          List.of("ai", "artificial intelligence", "tech", "technology", "cyber", "data",
              "software", "app", "startup", "digital", "elon", "tesla", "openai", "google",
              "meta")),
      new KeywordTheme("Health & Science", "🧬",
          List.of("health", "hospital", "cancer", "covid", "vaccine", "research", "medical",
              "science", "climate", "environment", "study", "treatment", "mental health",
              "nhs", "pandemic")),
      new KeywordTheme("Culture & Lifestyle", "🎭",
          List.of("art", "music", "film", "movie", "book", "festival", "restaurant", "food",
              "fashion", "celebrity", "award", "theatre", "entertainment", "culture",
              "travel", "lifestyle", "review")),
      new KeywordTheme("NSW & Sydney", "🌆",
          List.of("sydney", "nsw", "new south wales", "council", "transport", "infrastructure",
              "train", "bus", "road", "suburb", "local", "westmead", "parramatta", "bondi",
              "manly"))
  );

  private List<ThemeView> groupFromKeywords(List<Article> articles) {
    Map<String, List<Article>> buckets = new LinkedHashMap<>();
    Map<String, KeywordTheme> meta = new LinkedHashMap<>();
    for (Article a : articles) {
      KeywordTheme t = classifyKeyword(a);
      buckets.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(a);
      meta.putIfAbsent(t.name(), t);
    }
    buckets.values().forEach(BriefingRenderer::sortByDateDesc);
    return buckets.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, List<Article>>>comparingInt(e -> e.getValue().size()).reversed())
        .map(e -> new ThemeView(e.getKey(), meta.get(e.getKey()).emoji(), null, e.getValue()))
        .toList();
  }

  private KeywordTheme classifyKeyword(Article a) {
    String text = ((a.title() == null ? "" : a.title()) + " " +
                   (a.description() == null ? "" : a.description()) + " " +
                   (a.category() == null ? "" : a.category())).toLowerCase();
    for (KeywordTheme t : KEYWORD_THEMES) {
      for (String k : t.keywords()) {
        if (text.contains(k)) return t;
      }
    }
    return new KeywordTheme("Other", "📄", List.of());
  }

  // --- Helpers -------------------------------------------------------------

  private String pickSummary(Article a) {
    if (a.aiSummary() != null && !a.aiSummary().isBlank()) return a.aiSummary();
    return a.description() == null ? "" : a.description();
  }

  private String formatTime(Instant pubDate) {
    if (pubDate == null || pubDate.equals(Instant.EPOCH)) return "—";
    return TIME_LABEL.format(pubDate);
  }

  private static String usageCell(String label, String value) {
    return "  <div class=\"usage-item\"><span class=\"usage-label\">" + escape(label)
        + "</span><span class=\"usage-value\">" + escape(value) + "</span></div>\n";
  }

  private static String formatTokens(long n) {
    return String.format(Locale.ENGLISH, "%,d", n);
  }

  private static String formatCost(double usd) {
    // Costs are tiny — show 4 decimals so $0.0042 doesn't round to $0.00.
    return String.format(Locale.ENGLISH, "$%.4f", usd);
  }

  private static String formatRateLimit(ClaudeService.UsageStats u) {
    if (u.rateLimitTokensRemaining() == null || u.rateLimitTokensLimit() == null) {
      return "— (not reported)";
    }
    long remaining = u.rateLimitTokensRemaining();
    long limit = u.rateLimitTokensLimit();
    double pct = limit > 0 ? 100.0 * remaining / limit : 0;
    String base = String.format(Locale.ENGLISH, "%,d / %,d (%.0f%%)", remaining, limit, pct);
    if (u.rateLimitTokensReset() != null) {
      base += " · resets " + DateTimeFormatter
          .ofPattern("hh:mm a", Locale.ENGLISH)
          .withZone(SYDNEY)
          .format(u.rateLimitTokensReset());
    }
    return base;
  }

  /** Sydney-local label used in the since-banner: e.g. "8 Jun, 07:08 am". */
  private String formatSinceLabel(Instant since) {
    return DateTimeFormatter
        .ofPattern("d MMM, hh:mm a", Locale.ENGLISH)
        .withZone(SYDNEY)
        .format(since);
  }

  private static String escape(String s) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static String escapeAttr(String s) {
    return escape(s);
  }

  private String loadResource(String path) {
    try (InputStream in = getClass().getResourceAsStream(path)) {
      if (in == null) {
        log.error("Missing classpath resource: {}", path);
        return "";
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load {}: {}", path, e.getMessage());
      return "";
    }
  }
}
