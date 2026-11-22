import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class Wiki {
    static final URI SITE = URI.create("https://bg3.wiki");
    static final Path ROOT = Path.of(System.getProperty("forge.root", ".")).toAbsolutePath().normalize();
    static final Path RES = ROOT.resolve("src/main/resources");
    static final JsonMapper JSON = JsonMapper.builder().build();

    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] download(URI uri, String agent) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                URI current = uri;
                for (int redirects = 0; redirects < 6; redirects++) {
                    if (!"https".equals(current.getScheme()) || !"bg3.wiki".equals(current.getHost()))
                        throw new IllegalArgumentException("Unexpected wiki address: " + current);
                    var connection = (HttpURLConnection) current.toURL().openConnection();
                    try {
                        connection.setInstanceFollowRedirects(false);
                        connection.setConnectTimeout(25000);
                        connection.setReadTimeout(25000);
                        connection.setRequestProperty("User-Agent", agent);
                        int status = connection.getResponseCode();
                        if (status >= 300 && status < 400) {
                            current = current.resolve(connection.getHeaderField("Location"));
                            continue;
                        }
                        try (var input = connection.getInputStream()) { return input.readAllBytes(); }
                    } finally { connection.disconnect(); }
                }
                throw new IOException("Too many redirects for " + uri);
            } catch (IOException failure) {
                if (attempt == 3) throw new IOException("Could not download " + uri, failure);
                Thread.sleep(1000L * attempt);
            }
        }
    }

    static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".wiki-", ".tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }

    interface Work<T,R> { R apply(T input) throws Exception; }

    static <T,R> List<R> parallel(Collection<T> inputs, Work<T,R> work) throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var pending = new ArrayList<Future<R>>();
            for (T input : inputs) pending.add(pool.submit(() -> work.apply(input)));
            var output = new ArrayList<R>();
            for (var task : pending) output.add(task.get());
            return output;
        }
    }
}
