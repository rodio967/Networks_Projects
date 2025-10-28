package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.Location;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public class GraphHopperClient {
    private final HttpClient HTTP;
    private final ObjectMapper MAPPER;
    private final String apiKey = "fbc99b9e-3ecb-4cdc-b7f5-f14d80ac2762";
    private final ExecutorService EXEC;

    public GraphHopperClient(HttpClient httpClient, ObjectMapper objectMapper, ExecutorService exec) {
        this.HTTP = httpClient;
        this.MAPPER = objectMapper;
        this.EXEC = exec;
    }


    public CompletableFuture<List<Location>> searchLocations(String query) {
        String url = String.format(
                "https://graphhopper.com/api/1/geocode?q=%s&limit=10&key=%s",
                URLEncoder.encode(query, StandardCharsets.UTF_8), apiKey);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new CompletionException(
                                new RuntimeException("GraphHopper HTTP " + resp.statusCode() + ": " + resp.body()));
                    }
                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        List<Location> list = new ArrayList<>();
                        for (JsonNode h : root.path("hits")) {
                            String name = h.path("name").asText("");
                            String country = h.path("country").asText("");
                            String city = h.path("city").asText("");
                            double lat = h.path("point").path("lat").asDouble();
                            double lon = h.path("point").path("lng").asDouble();
                            list.add(new Location(name, country, city, lat, lon));
                        }

                        return list;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, EXEC);
    }
}
