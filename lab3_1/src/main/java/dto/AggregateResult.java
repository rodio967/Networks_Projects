package dto;

import java.util.List;

public record AggregateResult(Location location, Weather weather, List<PlaceFull> places) {}
