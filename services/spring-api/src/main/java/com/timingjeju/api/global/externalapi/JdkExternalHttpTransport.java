package com.timingjeju.api.global.externalapi;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

final class JdkExternalHttpTransport implements ExternalHttpTransport {

  private final BiFunction<ExternalApiProvider, Duration, HttpClient> clientFactory;

  JdkExternalHttpTransport() {
    Map<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();
    this.clientFactory =
        (provider, connectTimeout) ->
            clients.computeIfAbsent(
                new ClientKey(provider, connectTimeout),
                ignored ->
                    HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
  }

  JdkExternalHttpTransport(Function<ExternalApiProvider, HttpClient> clientFactory) {
    this.clientFactory = (provider, ignored) -> clientFactory.apply(provider);
  }

  @Override
  public ExternalHttpResponse exchange(
      ExternalHttpRequest request, Duration connectTimeout, Duration responseTimeout)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request.uri())
            .timeout(responseTimeout)
            .method(request.method().name(), HttpRequest.BodyPublishers.noBody());
    request.headers().forEach(builder::header);
    HttpResponse<java.io.InputStream> response =
        clientFactory
            .apply(request.provider(), connectTimeout)
            .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    return new ExternalHttpResponse(
        response.statusCode(), response.headers().map(), response.body());
  }

  private record ClientKey(ExternalApiProvider provider, Duration connectTimeout) {}
}
