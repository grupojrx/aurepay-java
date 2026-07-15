package com.aurepay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/** Facade principal da API AurePay para Java. */
public final class AurePay {
    private final HttpTransport transport;
    public final CrudResource deposits;
    public final CrudResource withdrawals;
    public final CrudResource webhooks;
    public final Company company;
    public final Conversions conversions;
    public final Chargebacks chargebacks;
    public final CrudResource wallets;

    public AurePay(String apiKey, String apiSecret) {
        this(apiKey, apiSecret, "https://api.aurepay.com.br/v1", 2);
    }

    /** Cria o cliente autenticado com X-Api-Key / X-Api-Secret. */
    public AurePay(String apiKey, String apiSecret, String baseUrl, int maxRetries) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new AurePayException("apiKey and apiSecret are required.", null, null, 0);
        }
        this.transport = new HttpTransport(apiKey.trim(), apiSecret.trim(), baseUrl, maxRetries);
        this.deposits = new CrudResource(transport, "/deposits");
        this.withdrawals = new CrudResource(transport, "/withdrawals");
        this.webhooks = new CrudResource(transport, "/webhooks");
        this.company = new Company(transport);
        this.conversions = new Conversions(transport);
        this.chargebacks = new Chargebacks(transport);
        this.wallets = new CrudResource(transport, "/wallets");
    }

    /** Transporte HTTP autenticado com retry em 429. */
    static final class HttpTransport {
        private final String apiKey;
        private final String apiSecret;
        private final String baseUrl;
        private final int maxRetries;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        private final Gson gson = new Gson();

        HttpTransport(String apiKey, String apiSecret, String baseUrl, int maxRetries) {
            this.apiKey = apiKey;
            this.apiSecret = apiSecret;
            this.baseUrl = baseUrl.replaceAll("/$", "");
            this.maxRetries = maxRetries;
        }

        /** Executa requisição autenticada e desempacota o envelope data. */
        JsonElement request(String method, String path, Object body, String idempotencyKey) {
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + path.replaceAll("^/", "")))
                        .timeout(Duration.ofSeconds(60))
                        .header("X-Api-Key", apiKey)
                        .header("X-Api-Secret", apiSecret)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json");

                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        builder.header("Idempotency-Key", idempotencyKey);
                    }

                    String payload = body == null ? null : gson.toJson(body);
                    builder.method(method.toUpperCase(), payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload));

                    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();

                    if (status == 429 && attempt <= maxRetries + 1) {
                        String retryAfter = response.headers().firstValue("Retry-After").orElse("1");
                        Thread.sleep(Math.max(1, Integer.parseInt(retryAfter)) * 1000L);
                        continue;
                    }

                    String raw = response.body() == null ? "" : response.body();
                    JsonElement decoded = raw.isBlank() ? null : JsonParser.parseString(raw);

                    if (status >= 400) {
                        String message = "Request failed.";
                        String code = null;
                        JsonElement details = null;
                        if (decoded != null && decoded.isJsonObject() && decoded.getAsJsonObject().has("error")) {
                            JsonObject error = decoded.getAsJsonObject().getAsJsonObject("error");
                            if (error.has("message")) {
                                message = error.get("message").getAsString();
                            }
                            if (error.has("code") && !error.get("code").isJsonNull()) {
                                code = error.get("code").getAsString();
                            }
                            if (error.has("details")) {
                                details = error.get("details");
                            }
                        }
                        throw new AurePayException(message, code, details, status);
                    }

                    if (decoded != null && decoded.isJsonObject() && decoded.getAsJsonObject().has("data")) {
                        return decoded.getAsJsonObject().get("data");
                    }
                    return decoded;
                } catch (AurePayException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AurePayException(e.getMessage() == null ? "HTTP request failed." : e.getMessage(), null, null, 0);
                }
            }
        }
    }

    /** Recurso CRUD genérico (list/create/get/update/delete). */
    public static class CrudResource {
        private final HttpTransport transport;
        private final String basePath;

        CrudResource(HttpTransport transport, String basePath) {
            this.transport = transport;
            this.basePath = basePath;
        }

        /** Lista recursos (GET). */
        public JsonElement list() {
            return transport.request("GET", basePath, null, null);
        }

        public JsonElement list(Map<String, ?> query) {
            if (query == null || query.isEmpty()) {
                return list();
            }
            StringJoiner joiner = new StringJoiner("&");
            for (Map.Entry<String, ?> entry : query.entrySet()) {
                joiner.add(enc(entry.getKey()) + "=" + enc(String.valueOf(entry.getValue())));
            }
            return transport.request("GET", basePath + "?" + joiner, null, null);
        }

        public JsonElement create(Object payload) {
            return create(payload, null);
        }

        public JsonElement create(Object payload, String idempotencyKey) {
            return transport.request("POST", basePath, payload, idempotencyKey);
        }

        public JsonElement get(String id) {
            return transport.request("GET", basePath + "/" + enc(id), null, null);
        }

        public JsonElement update(String id, Object payload) {
            return transport.request("PUT", basePath + "/" + enc(id), payload, null);
        }

        public JsonElement delete(String id) {
            return transport.request("DELETE", basePath + "/" + enc(id), null, null);
        }
    }

    /** Empresa autenticada e saldo. */
    public static final class Company {
        private final HttpTransport transport;

        Company(HttpTransport transport) {
            this.transport = transport;
        }

        /** Dados da empresa (GET /company). */
        public JsonElement get() {
            return transport.request("GET", "/company", null, null);
        }

        /** Saldo disponível (GET /company/balance). */
        public JsonElement balance() {
            return transport.request("GET", "/company/balance", null, null);
        }
    }

    /** Conversões BRL/USDT. */
    public static final class Conversions extends CrudResource {
        private final HttpTransport transport;

        Conversions(HttpTransport transport) {
            super(transport, "/conversions");
            this.transport = transport;
        }

        /** Cotação de conversão (POST /conversions/quote). */
        public JsonElement quote(Object payload) {
            return transport.request("POST", "/conversions/quote", payload, null);
        }
    }

    /** Infrações / MED. */
    public static final class Chargebacks {
        private final HttpTransport transport;

        Chargebacks(HttpTransport transport) {
            this.transport = transport;
        }

        public JsonElement list() {
            return transport.request("GET", "/chargebacks", null, null);
        }

        public JsonElement get(String id) {
            return transport.request("GET", "/chargebacks/" + enc(id), null, null);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8);
    }
}
