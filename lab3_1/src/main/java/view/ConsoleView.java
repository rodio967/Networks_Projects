package view;

import dto.AggregateResult;
import dto.PlaceFull;
import dto.Weather;

import java.util.Locale;

public class ConsoleView {
    public static void prettyPrint(AggregateResult res) {
        System.out.println("=== Результат ===");
        System.out.println("Локация: " + res.location());
        Weather w = res.weather();
        System.out.println(w);

        System.out.println("\nИнтересные места:");
        if (res.places().isEmpty()) {
            System.out.println("(ничего не найдено)");
        } else {
            for (int i = 0; i < res.places().size(); i++) {
                PlaceFull p =res.places().get(i);
                System.out.printf("%d) %s%n", i + 1, p.name());

                if (p.kinds() != null && !p.kinds().isBlank()) {
                    System.out.println("категории: " + p.kinds());
                }

                System.out.printf(Locale.ROOT, "координаты: %.5f, %.5f%n", p.lat(), p.lon());
                if (p.description() != null && !p.description().isBlank()) {
                    System.out.println("детали: " + p.description());
                }
            }
        }
    }
}
