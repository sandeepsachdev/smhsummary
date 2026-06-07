package com.smhsummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Builds the briefing once the Spring context is up and sends it as an email.
 * Runs synchronously so the application logs surface the result before
 * the web endpoints start serving traffic.
 */
@Component
@Order(0)
public class StartupRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

  private final BriefingService briefingService;
  private final EmailService emailService;

  public StartupRunner(BriefingService briefingService, EmailService emailService) {
    this.briefingService = briefingService;
    this.emailService = emailService;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("=== Startup briefing build ===");
    try {
      BriefingService.Briefing b = briefingService.build();
      emailService.send(b.subject(), b.emailHtml());
      log.info("=== Startup briefing complete ===");
    } catch (Exception e) {
      log.error("Startup briefing failed — the web endpoints will return a 500 until /refresh succeeds: {}",
          e.getMessage(), e);
    }
  }
}
