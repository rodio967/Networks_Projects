package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.Weather;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public class OpenWeatherClient {
    private final HttpClient HTTP;
    private final ObjectMapper MAPPER;
    private final String apiKey = "5570d73b9c24972436d5a203980a7ece";
    private final ExecutorService EXEC;

    public OpenWeatherClient(HttpClient httpClient, ObjectMapper objectMapper, ExecutorService exec) {
        this.HTTP = httpClient;
        this.MAPPER = objectMapper;
        this.EXEC = exec;
    }

    public CompletableFuture<Weather> fetchWeather(double lat, double lon) {
        String url = String.format(Locale.ROOT,
                "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&units=metric&lang=ru",
                lat, lon, apiKey);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new CompletionException(
                                new RuntimeException("OpenWeather HTTP " + resp.statusCode() + ": " + resp.body()));
                    }
                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        double temp = root.path("main").path("temp").asDouble();
                        double feels_like = root.path("main").path("feels_like").asDouble();
                        double wind = root.path("wind").path("speed").asDouble();
                        String desc = root.path("weather").isArray() && root.path("weather").size() > 0
                                ? root.path("weather").get(0).path("description").asText()
                                : "";
                        return new Weather(temp, feels_like, desc, wind);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, EXEC);
    }
}
