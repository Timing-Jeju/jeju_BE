package com.timingjeju.api.global.text;

import com.timingjeju.api.application.text.PublicPlainTextNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public final class JsoupPublicPlainTextNormalizer implements PublicPlainTextNormalizer {

  private static final String NON_PUBLIC_ELEMENTS =
      "script,style,template,noscript,iframe,object,embed,svg,math";

  @Override
  public String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    Document document = Jsoup.parseBodyFragment(value);
    document.select(NON_PUBLIC_ELEMENTS).before(" ").after(" ").remove();
    String decoded = document.body().text().replace('\u00a0', ' ');
    StringBuilder normalized = new StringBuilder(Math.min(decoded.length(), MAX_CODE_POINTS));
    boolean pendingSpace = false;
    int written = 0;
    for (int offset = 0; offset < decoded.length() && written < MAX_CODE_POINTS; ) {
      int codePoint = decoded.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
        continue;
      }
      if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
        pendingSpace = normalized.length() > 0;
        continue;
      }
      if (pendingSpace && written < MAX_CODE_POINTS) {
        normalized.append(' ');
        written++;
      }
      pendingSpace = false;
      if (written < MAX_CODE_POINTS) {
        normalized.appendCodePoint(codePoint);
        written++;
      }
    }
    return normalized.isEmpty() ? null : normalized.toString();
  }
}
