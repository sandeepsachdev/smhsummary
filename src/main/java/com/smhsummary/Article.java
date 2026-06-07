package com.smhsummary;

import java.time.Instant;

public record Article(
    String title,
    String link,
    String description,
    String category,
    Instant pubDate,
    String aiSummary
) {
  public Article withSummary(String summary) {
    return new Article(title, link, description, category, pubDate, summary);
  }
}
