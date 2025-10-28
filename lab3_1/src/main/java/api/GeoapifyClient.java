package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.PlaceBrief;
import dto.PlaceFull;

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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public class GeoapifyClient {
    private final HttpClient HTTP;
    private final ObjectMapper MAPPER;
    private final String apiKey = "8038ddfd511741f09ced66f7f2111d86";
    private final ExecutorService EXEC;

    public GeoapifyClient(HttpClient httpClient, ObjectMapper objectMapper, ExecutorService exec) {
        this.HTTP = httpClient;
        this.MAPPER = objectMapper;
        this.EXEC = exec;
    }

    public CompletableFuture<List<PlaceBrief>> fetchPlaces(double lat, double lon) {
        String url = String.format(Locale.ROOT,
                "https://api.geoapify.com/v2/places?categories=tourism.attraction,leisure.park,entertainment,heritage&filter=circle:%.6f,%.6f,3000&limit=15&apiKey=%s",
                lon, lat, apiKey);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new CompletionException(
                                new RuntimeException("Geoapify HTTP " + resp.statusCode() + ": " + resp.body()));
                    }
                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        List<PlaceBrief> list = new ArrayList<>();
                        for (JsonNode f : root.path("features")) {
                            JsonNode p = f.path("properties");
                            String id = p.path("place_id").asText("");
                            String name = p.path("name").asText("(без названия)");
                            String cats = p.path("categories").asText("");
                            double dLat = p.path("lat").asDouble();
                            double dLon = p.path("lon").asDouble();
                            double dist = p.path("distance").asDouble(0);
                            if (id != null && !id.isBlank()) {
                                list.add(new PlaceBrief(id, name, dLat, dLon, dist, cats));
                            }
                        }
                        return list;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, EXEC);
    }


    public CompletableFuture<PlaceFull> fetchPlaceDetail(String id) {
        String url = String.format(
                "https://api.geoapify.com/v2/place-details?id=%s&apiKey=%s",
                URLEncoder.encode(id, StandardCharsets.UTF_8), apiKey);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        return new PlaceFull(id, "(недоступно)", "", "", 0, 0);
                    }

                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        JsonNode features = root.path("features");
                        if (!features.isArray() || features.size() == 0) {
                            return new PlaceFull(id, "(не найдено)", "", "", 0, 0);
                        }

                        JsonNode props = features.get(0).path("properties");

                        String name = props.path("name").asText("(без названия)");
                        String categories = props.path("categories").asText();
                        double lat = props.path("lat").asDouble(0);
                        double lon = props.path("lon").asDouble(0);

                        StringBuilder desc = new StringBuilder();

                        String formatted = props.path("formatted").asText();
                        if (formatted != null && !formatted.isBlank()) {
                            desc.append("Адрес: ").append(formatted);
                        }

                        String website = props.path("website").asText();
                        if (website != null && !website.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Сайт: ").append(website);
                        }

                        String phone = props.path("phone").asText();
                        if (phone != null && !phone.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Телефон: ").append(phone);
                        }

                        String email = props.path("email").asText();
                        if (email != null && !email.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Email: ").append(email);
                        }


                        String opening = props.path("opening_hours").asText();
                        if (opening != null && !opening.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Часы работы: ").append(opening);
                        }


                        String wikipedia = props.path("wikipedia").asText();
                        if (wikipedia != null && !wikipedia.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Wikipedia: ").append(wikipedia);
                        }


                        String osmLink = props.path("osm").asText();
                        if (osmLink != null && !osmLink.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("OSM: ").append(osmLink);
                        }


                        String text = props.path("description").asText();
                        if (text == null || text.isBlank()) {
                            text = props.path("text").asText();
                        }
                        if (text != null && !text.isBlank()) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append(text);
                        }


                        String shortName = props.path("short_name").asText();
                        if (shortName != null && !shortName.isBlank() && !shortName.equalsIgnoreCase(name)) {
                            if (!desc.isEmpty()) desc.append("\n");
                            desc.append("Также известно как: ").append(shortName);
                        }

                        String resultDesc = desc.isEmpty() ? "Описание недоступно" : desc.toString();

                        return new PlaceFull(id, name, categories, resultDesc, lat, lon);
                    } catch (Exception e) {
                        System.err.println("Ошибка парсинга place-details: " + e.getMessage());
                        return new PlaceFull(id, "(ошибка парсинга)", "", "", 0, 0);
                    }
                }, EXEC);
    }


    public CompletableFuture<List<PlaceFull>> fetchAllPlaceDetails(List<PlaceBrief> briefs) {
        List<CompletableFuture<PlaceFull>> detailFutures = new ArrayList<>();
        for (PlaceBrief placeBrief : briefs) {
            CompletableFuture<PlaceFull> detail = fetchPlaceDetail(placeBrief.id());
            detailFutures.add(detail);
        }

        CompletableFuture<?>[] futureArray = detailFutures.toArray(new CompletableFuture[0]);
        CompletableFuture<Void> allFutureDone = CompletableFuture.allOf(futureArray);

        return allFutureDone.thenApplyAsync(v -> {
            List<PlaceFull> list = new ArrayList<>();
            for (CompletableFuture<PlaceFull> future : detailFutures) {
                PlaceFull detail = future.join();
                list.add(detail);
            }

            return list;
        }, EXEC);

    }
}
