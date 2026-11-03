package com.prizm;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public WebConfiguration(@Value("${prizm.cors-origins:}") String configured) {
    origins =
        Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(
                value -> {
                  URI uri = URI.create(value);
                  if (uri.getScheme() == null
                      || !java.util.Set.of("http", "https").contains(uri.getScheme())
                      || uri.getHost() == null
                      || uri.getUserInfo() != null
                      || uri.getQuery() != null
                      || uri.getFragment() != null
                      || !uri.getPath().isEmpty())
                    throw new IllegalArgumentException(
                        "CORS requires exact origins without paths or wildcards");
                  return value;
                })
            .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (origins.length == 0) return;
    registry
        .addMapping("/api/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type", "X-Request-Id")
        .exposedHeaders("X-Request-Id")
        .allowCredentials(false)
        .maxAge(3600);
  }
}
