package dto;

import java.util.Locale;

public record Weather(double tempC, double feels_like, String description, double windMs) {
    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "Погода: %.1f C, ощущается как %.1f C, ветер %.1f м/с, %s",
                tempC, feels_like, windMs, description != null
                        ? description()
                        : "");
    }
}
