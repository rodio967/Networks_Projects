import com.fasterxml.jackson.databind.ObjectMapper;
import dto.AggregateResult;
import dto.Location;
import service.TravelService;
import util.HttpUtils;
import util.JsonUtils;
import view.ConsoleView;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws  Exception {
        HttpClient HTTP = HttpUtils.createHttp();
        ObjectMapper MAPPER = JsonUtils.createMapper();
        ExecutorService EXEC = Executors.newFixedThreadPool(4);

        TravelService travelService = new TravelService(HTTP, MAPPER, EXEC);

        try (Scanner sc = new Scanner(System.in)) {
            System.out.print("Введите название места: ");
            String query = sc.nextLine().trim();

            List<Location> options = travelService.searchLocations(query).get(30, TimeUnit.SECONDS);
            if (options.isEmpty()) {
                System.out.println("Ничего не найдено.");
                return;
            }

            System.out.println("\nНайдены варианты:");
            for (int i = 0; i < options.size(); i++) {
                System.out.printf("%d) %s%n", i + 1, options.get(i));
            }
            System.out.print("Выберите номер локации: ");
            int idx = -1;
            while (idx < 1 || idx > options.size()) {
                String s = sc.nextLine().trim();
                try {
                    idx = Integer.parseInt(s);
                } catch (NumberFormatException ignore) {}
                if (idx < 1 || idx > options.size()) {
                    System.out.print("Некорректный номер, попробуйте снова: ");
                }
            }

            Location selected = options.get(idx - 1);

            AggregateResult result = travelService.buildResult(selected).get(90, TimeUnit.SECONDS);

            ConsoleView.prettyPrint(result);
        } finally {
            EXEC.shutdown();
        }
    }
}
