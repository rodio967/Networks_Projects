package service;

import api.GeoapifyClient;
import api.GraphHopperClient;
import api.OpenWeatherClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.*;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;


public class TravelService {
    private final GraphHopperClient geoClient;
    private final OpenWeatherClient weatherClient;
    private final GeoapifyClient placesClient;
    private final ExecutorService EXEC;

    public TravelService(HttpClient HTTP, ObjectMapper MAPPER, ExecutorService EXEC) {
        this.geoClient = new GraphHopperClient(HTTP, MAPPER, EXEC);
        this.weatherClient = new OpenWeatherClient(HTTP, MAPPER, EXEC);
        this.placesClient = new GeoapifyClient(HTTP, MAPPER, EXEC);
        this.EXEC = EXEC;
    }

    public CompletableFuture<AggregateResult> buildResult(Location loc) {
        CompletableFuture<Weather> fWeather = weatherClient.fetchWeather(loc.lat(), loc.lon());
        CompletableFuture<List<PlaceBrief>> fPlaces = placesClient.fetchPlaces(loc.lat(), loc.lon());

        CompletableFuture<List<PlaceFull>> fDetails = fPlaces.thenComposeAsync(placesClient::fetchAllPlaceDetails, EXEC);
        return fWeather.thenCombineAsync(fDetails,
                (weather, details) -> new AggregateResult(loc, weather, details), EXEC);
    }

    public CompletableFuture<List<Location>> searchLocations(String query) {
        return geoClient.searchLocations(query);
    }
}
