import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

class Art {
    static final Path RES = Path.of(System.getProperty("forge.root", ".")).toAbsolutePath().normalize().resolve("src/main/resources");
    static final Path OUT = RES.resolve("static/art/avatars");
    static final List<String> CLASSES = List.of("Barbarian", "Bard", "Cleric", "Druid", "Fighter", "Monk",
            "Paladin", "Ranger", "Rogue", "Sorcerer", "Warlock", "Wizard");

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static BufferedImage resize(BufferedImage image, int width, int height) {
        do {
            int nextWidth = Math.max(width, image.getWidth() / 2);
            int nextHeight = Math.max(height, image.getHeight() / 2);
            var next = new BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            var g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, 0, 0, nextWidth, nextHeight, null);
            g.dispose();
            image = next;
        } while (image.getWidth() != width || image.getHeight() != height);
        return image;
    }

    static BufferedImage fit(BufferedImage image, int width, int height, boolean enlarge) {
        double factor = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
        if (!enlarge && factor >= 1) return image;
        return resize(image, Math.max(1, (int)Math.round(image.getWidth() * factor)),
                Math.max(1, (int)Math.round(image.getHeight() * factor)));
    }

    static void ffmpeg(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-y"));
        command.addAll(Arrays.asList(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            if (process.waitFor() != 0) throw new IOException("FFmpeg failed: " + command);
        } finally { process.destroy(); }
    }

    static BufferedImage read(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image != null) return image;
        Path png = Files.createTempFile("forge-decoded-", ".png");
        try {
            ffmpeg("-i", path.toString(), "-frames:v", "1", png.toString());
            return ImageIO.read(png.toFile());
        } finally { Files.deleteIfExists(png); }
    }

    static void webp(BufferedImage image, Path destination, int quality, int method) throws Exception {
        Files.createDirectories(destination.getParent());
        Path png = Files.createTempFile("forge-image-", ".png");
        try {
            ImageIO.write(image, "png", png.toFile());
            Path encoded = Files.createTempFile(destination.getParent(), ".forge-", ".webp");
            try {
                ffmpeg("-i", png.toString(), "-frames:v", "1", "-c:v", "libwebp", "-quality", "" + quality,
                        "-compression_level", "" + method, encoded.toString());
                Files.move(encoded, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(encoded); }
        } finally { Files.deleteIfExists(png); }
    }
}
