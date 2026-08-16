package com.timingjeju.api.global.tourapi.detailitem;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public final class DetailItemContentSanitizer {
  private static final Safelist TEXT_STRUCTURE =
      Safelist.none().addTags("p", "br", "div", "ul", "ol", "li", "strong", "em", "b", "i");

  String plainText(String rawHtml) {
    Document source = Jsoup.parseBodyFragment(rawHtml);
    source
        .select("script,style,template,noscript,iframe,object,embed,svg,math")
        .before(" ")
        .after(" ")
        .remove();
    source.select("strong,em,b,i,a").before(" ").after(" ");
    String safeHtml = Jsoup.clean(source.body().html(), TEXT_STRUCTURE);
    String wholeText = Jsoup.parseBodyFragment(safeHtml).body().wholeText();
    return Arrays.stream(wholeText.replace('\u00a0', ' ').split("\\R"))
        .map(line -> line.strip().replaceAll("[\\t ]+", " "))
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining("\n"));
  }

  String safeUrl(String rawUrl) {
    try {
      URI uri = URI.create(rawUrl.strip()).normalize();
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.toLowerCase(Locale.ROOT).equals("http")
              || scheme.toLowerCase(Locale.ROOT).equals("https"))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        return null;
      }
      return uri.toASCIIString();
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
