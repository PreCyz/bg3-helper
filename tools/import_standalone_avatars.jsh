/open tools/art.jsh

import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

class StandaloneAvatars {
    // Measure the centre of the head band on the cutout. This keeps a bow or shield
    // on one side of the body from shifting a party portrait away from the face.
    static int headCenter(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] mass = new int[width];
        int total = 0;
        for (int y = height / 16; y < height / 6; y += 2) {
            for (int x = width / 6; x < width * 5 / 6; x++) {
                if ((image.getRGB(x, y) >>> 24) >= 160) {
                    mass[x]++;
                    total++;
                }
            }
        }
        if (total == 0) return width / 2;
        int cumulative = 0;
        for (int x = 0; x < width; x++) {
            cumulative += mass[x];
            if (cumulative * 2 >= total) return x;
        }
        return width / 2;
    }

    static void importImage(Path png, Path character) throws Exception {
        Path face = character.getParent().resolve("portraits").resolve(character.getFileName());
        var original = Art.read(png);
        if (!original.getColorModel().hasAlpha() || original.getWidth() < 640 || original.getHeight() < 960)
            throw new IOException("Expected a high-resolution transparent source: " + png);
        int left = original.getWidth(), top = original.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                if ((original.getRGB(x, y) >>> 24) < 24) continue;
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        if (bottom <= top || right <= left) throw new IOException("Empty image: " + png);
        var subject = original.getSubimage(left, top, right - left + 1, bottom - top + 1);
        var fitted = Art.fit(subject, 608, 912, true);
        var figure = new BufferedImage(640, 960, BufferedImage.TYPE_INT_ARGB_PRE);
        var graphics = figure.createGraphics();
        graphics.drawImage(fitted, (640 - fitted.getWidth()) / 2, (960 - fitted.getHeight()) / 2, null);
        graphics.dispose();
        int side = Math.max(1, Math.min(subject.getWidth(), subject.getHeight() / 4));
        int x = Math.clamp(headCenter(subject) - side / 2, 0, subject.getWidth() - side);
        var portrait = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        graphics = portrait.createGraphics();
        graphics.setColor(new Color(0x171c1a));
        graphics.fillRect(0, 0, side, side);
        graphics.drawImage(subject, -x, side / 25, null);
        graphics.dispose();
        Path v2 = Art.OUT.resolve("figures-v2");
        Art.webp(figure, v2.resolve(character), 95, 5);
        Art.webp(Art.resize(portrait, 202, 202), v2.resolve(face), 94, 5);
        Files.copy(v2.resolve(character), Art.OUT.resolve("figures").resolve(character), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(v2.resolve(face), Art.OUT.resolve("portraits").resolve(character), StandardCopyOption.REPLACE_EXISTING);
    }

    static void run() throws Exception {
        var input = Path.of(System.getProperty("avatar.input"));
        var manifestFile = Art.RES.resolve("asset-paths.txt");
        var manifest = new TreeSet<>(Files.readAllLines(manifestFile));
        try (var paths = Files.walk(input)) {
            for (var png : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                var key = input.relativize(png);
                var character = key.resolveSibling(key.getFileName().toString().replaceFirst("[.]png$", ".webp"));
                if (!Files.isRegularFile(Art.OUT.resolve("figures").resolve(character)))
                    throw new IOException("Unknown avatar " + character);
                importImage(png, character);
                System.out.println(character);
            }
        }
        // Recover any pair whose files were published just before a previous import was interrupted.
        Path versioned = Art.OUT.resolve("figures-v2");
        try (var files = Files.walk(versioned)) {
            for (var figure : files.filter(Files::isRegularFile).toList()) {
                String name = figure.getFileName().toString();
                if (name.startsWith(".") || !name.endsWith(".webp") || figure.getParent().endsWith("portraits")) continue;
                Path face = figure.resolveSibling("portraits").resolve(name);
                if (Files.isRegularFile(face)) {
                    for (Path file : List.of(figure, face))
                        manifest.add("/art/avatars/figures-v2/" + versioned.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        Path staged = Files.createTempFile(manifestFile.getParent(), ".asset-paths-", ".tmp");
        try {
            Files.writeString(staged, String.join("\n", manifest) + "\n", StandardCharsets.UTF_8);
            Files.move(staged, manifestFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }
}

int importStatus = 1;
try { StandaloneAvatars.run(); importStatus = 0; } catch (Exception e) { e.printStackTrace(System.err); }
/exit importStatus
