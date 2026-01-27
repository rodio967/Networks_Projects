package socks5.auth;

import socks5.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class AuthConfig {
    private AuthType authType;
    private final Map<String, String> users = new ConcurrentHashMap<>();

    public AuthConfig(String filename) throws IOException {
        Path path = Path.of(filename);

        if (!Files.exists(path)) {
            Log.log("Auth file '%s' not found", filename);
            throw new FileNotFoundException("Auth Config file not found: " + path);
        }

        Properties props = new Properties();
        try (InputStream input = new FileInputStream(path.toFile())) {
            props.load(input);
        } catch (IOException e) {
            Log.log("Error reading auth file '%s': %s", filename, e.getMessage());
            throw e;
        }

        selectType(props);
        if (authType == AuthType.AUTH) {
            loadUsers(props);
        }
    }

    private void selectType(Properties props) {
        String type = props.getProperty("type");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalStateException("Property 'type' not found. Expected AUTH or NO_AUTH");
        }

        try {
            this.authType = AuthType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid auth type: " + type + ". Expected AUTH or NO_AUTH");
        }
    }

    private void loadUsers(Properties props) {
        for (String key : props.stringPropertyNames()) {
            if (key.equals("type") || key.isEmpty() || props.getProperty(key) == null || props.getProperty(key).isEmpty()) {
                continue;
            }

            users.put(key, props.getProperty(key));
        }
    }

    public boolean isEnabled() {
        return authType == AuthType.AUTH;
    }

    public boolean checkCredentials(String name, String pass) {
//        if (!isEnabled()) return true;

        if (name == null || pass == null) return false;

        String p = users.get(name);
        return p != null && p.equals(pass);
    }
}
