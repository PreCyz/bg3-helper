/open tools/art.jsh

import java.util.ArrayDeque;
import java.util.HashSet;

class Atlas {
    static int channel(int rgb, int index) { return (rgb >>> (16 - index * 8)) & 255; }

    static int median(int[] counts, int size) {
        int lower = (size - 1) / 2, upper = size / 2, seen = 0, left = -1;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (left < 0 && seen > lower) left = i;
            if (seen > upper) return left + i; // Twice the median, including half-integers.
        }
        throw new IllegalArgumentException("Empty sample");
    }

    static int distance(int pixel, int[] background) {
        int max = 0;
        for (int i = 0; i < 3; i++) max = Math.max(max, Math.abs(channel(pixel, i) - background[i]));
        return max;
    }

    static int[] gutters(int[] profile, int count) {
        int[] cuts = new int[count + 1];
        cuts[count] = profile.length;
        for (int i = 1; i < count; i++) {
            int middle = (int)Math.rint((double)i * profile.length / count);
            int radius = (int)Math.rint(.27 * profile.length / count);
            int start = middle - radius, end = middle + radius;
            int min = Arrays.stream(profile, start, end + 1).min().orElseThrow();
            int longest = 0;
            double best = middle;
            for (int x = start; x <= end; x++) {
                if (profile[x] != min) continue;
                int first = x;
                while (x < end && profile[x + 1] == min) x++;
                double center = (first + x) / 2.0;
                int length = x - first + 1;
                if (length > longest || (length == longest && Math.abs(center-middle) < Math.abs(best-middle))) {
                    longest = length;
                    best = center;
                }
            }
            cuts[i] = start + (int)Math.rint(best - start);
        }
        return cuts;
    }

    static BufferedImage cutout(BufferedImage tile) {
        var small = Art.fit(tile, 150, 190, false);
        int w = small.getWidth(), h = small.getHeight();
        int[] background = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] corners = new int[4];
            int n = 0;
            for (int x : new int[]{1,w-2}) for (int y : new int[]{1,h-2}) corners[n++] = channel(small.getRGB(x,y), c);
            Arrays.sort(corners);
            background[c] = (int)Math.rint((corners[1] + corners[2]) / 2.0);
        }
        // Flood from all edges. Dark areas enclosed by the figure keep their opacity.
        boolean[] backdrop = new boolean[w*h];
        var queue = new ArrayDeque<Integer>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (x == 0 || x == w-1 || y == 0 || y == h-1) queue.add(y*w+x);
        }
        while (!queue.isEmpty()) {
            int pos = queue.removeFirst(), x = pos % w, y = pos / w;
            if (backdrop[pos] || distance(small.getRGB(x,y), background) >= 20) continue;
            backdrop[pos] = true;
            if (x > 0) queue.add(pos-1);
            if (x+1 < w) queue.add(pos+1);
            if (y > 0) queue.add(pos-w);
            if (y+1 < h) queue.add(pos+w);
        }
        var mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < backdrop.length; i++) mask.setRGB(i%w, i/w, backdrop[i] ? 0 : 0xffffffff);
        int width = tile.getWidth(), height = tile.getHeight();
        var alpha = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = alpha.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(mask, 0, 0, width, height, null);
        g.dispose();
        var body = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int opacity = alpha.getRGB(x,y) & 0xff000000;
            if (opacity == 0) continue;
            body.setRGB(x,y,opacity | (tile.getRGB(x,y) & 0xffffff));
            left = Math.min(left,x); right = Math.max(right,x);
            top = Math.min(top,y); bottom = Math.max(bottom,y);
        }
        return right < left ? body : body.getSubimage(left, top, right-left+1, bottom-top+1);
    }

    static BufferedImage figure(BufferedImage sprite) {
        var frame = new BufferedImage(320, 480, BufferedImage.TYPE_INT_ARGB);
        var body = Art.fit(sprite, 300, 452, true);
        var g = frame.createGraphics();
        g.drawImage(body, (320-body.getWidth())/2, (480-body.getHeight())/2, null);
        g.dispose();
        return frame;
    }

    static BufferedImage portrait(BufferedImage sprite) {
        int w = sprite.getWidth(), h = sprite.getHeight(), side = Math.max(1, (int)Math.rint(h * .39));
        int[] columns = new int[w];
        int pixels = 0;
        for (int y = (int)Math.rint(.07*h); y < (int)Math.rint(.17*h); y++) for (int x = 0; x < w; x++) {
            if ((sprite.getRGB(x,y) >>> 24) > 128) { columns[x]++; pixels++; }
        }
        int center = pixels == 0 ? w/2 : (int)Math.rint(median(columns, pixels)/2.0);
        var face = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        var g = face.createGraphics();
        g.setColor(new Color(0x181b19));
        g.fillRect(0, 0, side, side);
        g.drawImage(sprite, side/2 - center, (int)Math.rint(side * .08), null);
        g.dispose();
        return Art.resize(face, 160, 160);
    }

    static void run() throws Exception {
        String race = Art.slug(System.getProperty("race", ""));
        String file = System.getProperty("atlas", "");
        if (race.isEmpty() || file.isBlank())
            throw new IllegalArgumentException("Supply -R-Drace=\"Wood Elf\" -R-Datlas=sheet.png and optionally -R-Dpaired=true.");
        boolean paired = Boolean.getBoolean("paired");
        int cols = paired ? 6 : 4, rows = paired ? 4 : 3;
        var hirelings = new HashSet<String>();
        for (String line : Files.readAllLines(Art.OUT.resolve("credits.txt"))) {
            int separator = line.indexOf(": https://");
            if (separator > 0) hirelings.add(line.substring(0, separator));
        }
        var image = Art.read(Path.of(file));
        int width = image.getWidth(), height = image.getHeight();
        if (width < cols*4 || height < rows*4) throw new IllegalArgumentException("Atlas is too small for this layout");
        int[][] histogram = new int[3][256];
        int samples = 0;
        for (int y = 0; y < height; y+=4) for (int x = 0; x < width; x+=4) {
            int rgb = image.getRGB(x,y);
            for (int c = 0; c < 3; c++) histogram[c][channel(rgb,c)]++;
            samples++;
        }
        int[] background = new int[3];
        for (int c = 0; c < 3; c++) background[c] = median(histogram[c],samples)/2;
        var foreground = new java.util.BitSet(width*height);
        int[] horizontal = new int[width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (distance(image.getRGB(x,y),background) > 20) { foreground.set(y*width+x); horizontal[x]++; }
        }
        int[] xCuts = gutters(horizontal, cols);
        for (int col = 0; col < cols; col++) {
            int left = xCuts[col], right = xCuts[col+1];
            int[] vertical = new int[height];
            for (int y = 0; y < height; y++)
                for (int x = left; x < right; x++) if (foreground.get(y*width+x)) vertical[y]++;
            int[] yCuts = gutters(vertical, rows);
            for (int row = 0; row < rows; row++) {
                int i = row*cols+col;
                String key = race + "/" + Art.slug(Art.CLASSES.get(paired ? i/2 : i));
                String path = (paired ? (i%2 == 0 ? "male/" : "female/") : "") + key + ".webp";
                var sprite = cutout(image.getSubimage(left, yCuts[row], right-left, yCuts[row+1]-yCuts[row]));
                Art.webp(figure(sprite), Art.OUT.resolve("figures").resolve(path), 88, 4);
                if (paired || !hirelings.contains(key)) Art.webp(portrait(sprite), Art.OUT.resolve("portraits").resolve(path), 90, 4);
            }
        }
        System.out.println("Imported " + (cols*rows) + " figures for " + race + ".");
    }
}

int atlasExit = 1;
try { Atlas.run(); atlasExit = 0; } catch (Exception e) { e.printStackTrace(System.err); }
/exit atlasExit
