package util;

import java.net.http.HttpClient;
import java.time.Duration;

public class HttpUtils {
    public static HttpClient createHttp() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }
}
