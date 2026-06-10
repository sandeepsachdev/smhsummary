package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FeedService {
  private static final Logger log = LoggerFactory.getLogger(FeedService.class);

  // Mirrors the section feeds the browser app pulls. Sport feeds are
  // intentionally absent — we don't want sport stories anywhere.
  private static final List<String> FEEDS = List.of(
      "https://www.smh.com.au/rss/feed.xml",
      "https://www.smh.com.au/rss/national.xml",
      "https://www.smh.com.au/rss/national/nsw.xml",
      "https://www.smh.com.au/rss/politics/federal.xml",
      "https://www.smh.com.au/rss/world.xml",
      "https://www.smh.com.au/rss/business.xml",
      "https://www.smh.com.au/rss/technology.xml",
      "https://www.smh.com.au/rss/culture.xml",
      "https://www.smh.com.au/rss/lifestyle.xml",
      "https://www.smh.com.au/rss/opinion.xml",
      "https://www.smh.com.au/rss/environment.xml",
      "https://www.smh.com.au/rss/money.xml",
      "https://www.smh.com.au/rss/property.xml"
  );

  private static final Set<String> SPORT_KEYWORDS = Set.of(
      "nrl", "afl", "cricket", "rugby", "tennis", "golf", "football", "soccer",
      "swim", "olympic", "champion", "match", "game", "player", "team", "coach",
      "league", "final", "grand prix"
  );

  // SMH article URLs end in something like "-p5n2x4.html" — that slug is
  // the unique article ID and survives tracking/source params.
  private static final Pattern SLUG = Pattern.compile("-p\\d+[a-z0-9]+\\.html?", Pattern.CASE_INSENSITIVE);

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public record FetchResult(
      List<Article> articles,
      int feedsSucceeded,
      int feedsFailed,
      int feedsTotal,
      int rawItems,
      int sportFiltered,
      long fetchDurationMs
  ) {}

  public FetchResult fetchAll() {
    long startNanos = System.nanoTime();
    List<CompletableFuture<List<Article>>> futures = FEEDS.stream()
        .map(this::fetchOneAsync)
        .toList();

    int succeeded = 0, failed = 0, raw = 0;
    Set<String> seen = new HashSet<>();
    List<Article> deduped = new ArrayList<>();

    for (int i = 0; i < futures.size(); i++) {
      try {
        List<Article> items = futures.get(i).get();
        if (items == null) {
          failed++;
          continue;
        }
        succeeded++;
        for (Article a : items) {
          raw++;
          String key = articleKey(a.link());
          if (key == null || !seen.add(key)) continue;
          deduped.add(a);
        }
      } catch (Exception e) {
        failed++;
        log.warn("Feed {} failed: {}", FEEDS.get(i), e.getMessage());
      }
    }

    int beforeSport = deduped.size();
    List<Article> kept = deduped.stream()
        .filter(a -> !isSport(a))
        .sorted(Comparator.comparing(
            Article::pubDate,
            Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
    int sportFiltered = beforeSport - kept.size();

    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info("Feeds: {}/{} ok · {} raw · {} unique · {} sport filtered · {} kept · {}ms",
        succeeded, FEEDS.size(), raw, beforeSport, sportFiltered, kept.size(), durationMs);

    return new FetchResult(kept, succeeded, failed, FEEDS.size(), raw, sportFiltered, durationMs);
  }

  private CompletableFuture<List<Article>> fetchOneAsync(String url) {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", "Mozilla/5.0 (compatible; SMH-Briefing/1.0)")
        .header("Accept", "application/rss+xml, application/xml, text/xml")
        .GET()
        .build();
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          if (res.statusCode() != 200) {
            log.warn("Feed {} returned HTTP {}", url, res.statusCode());
            return List.<Article>of();
          }
          return parseRss(res.body());
        })
        .exceptionally(e -> {
          log.warn("Feed {} fetch failed: {}", url, e.getMessage());
          return null;
        });
  }

  private List<Article> parseRss(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
      NodeList items = doc.getElementsByTagName("item");
      List<Article> articles = new ArrayList<>();
      for (int i = 0; i < items.getLength(); i++) {
        Element item = (Element) items.item(i);
        String title = text(item, "title");
        String link = text(item, "link");
        if (title.isBlank() || link.isBlank()) continue;
        String description = stripHtml(text(item, "description"));
        if (description.length() > 220) description = description.substring(0, 220) + "…";
        String category = text(item, "category");
        Instant pubDate = parseDate(text(item, "pubDate"));
        articles.add(new Article(title, link, description, category, pubDate, null));
      }
      return articles;
    } catch (Exception e) {
      log.warn("RSS parse failed: {}", e.getMessage());
      return List.of();
    }
  }

  private String text(Element parent, String tag) {
    NodeList nl = parent.getElementsByTagName(tag);
    if (nl.getLength() == 0) return "";
    String s = nl.item(0).getTextContent();
    return s == null ? "" : s.trim();
  }

  private String stripHtml(String s) {
    if (s == null) return "";
    return s.replaceAll("<[^>]+>", "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }

  private Instant parseDate(String s) {
    if (s == null || s.isEmpty()) return Instant.EPOCH;
    try {
      return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s, Instant::from);
    } catch (Exception e) {
      return Instant.EPOCH;
    }
  }

  private String articleKey(String url) {
    if (url == null || url.isEmpty()) return null;
    Matcher m = SLUG.matcher(url);
    if (m.find()) return m.group().toLowerCase();
    try {
      URI u = URI.create(url);
      String host = u.getHost() == null ? "" : u.getHost();
      String path = u.getPath() == null ? "" : u.getPath();
      return (host + path).replaceAll("/+$", "").toLowerCase();
    } catch (Exception e) {
      int q = url.indexOf('?');
      int h = url.indexOf('#');
      int end = url.length();
      if (q >= 0 && q < end) end = q;
      if (h >= 0 && h < end) end = h;
      return url.substring(0, end).toLowerCase();
    }
  }

  private boolean isSport(Article a) {
    String cat = a.category() == null ? "" : a.category().toLowerCase();
    if (cat.contains("sport")) return true;
    String text = ((a.title() == null ? "" : a.title()) + " " +
                   (a.description() == null ? "" : a.description())).toLowerCase();
    for (String k : SPORT_KEYWORDS) {
      if (text.contains(k)) return true;
    }
    return false;
  }
}
