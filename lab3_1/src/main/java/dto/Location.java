package dto;

import java.util.ArrayList;
import java.util.List;

public record Location(String name, String country, String city, double lat, double lon) {
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (name != null && !name.isBlank()) parts.add(name);
        if (city != null && !city.isBlank()) parts.add(city);
        if (country != null && !country.isBlank()) parts.add(country);
        return String.join(", ", parts) + String.format(" [%.5f, %.5f]", lat, lon);
    }
}
