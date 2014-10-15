import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;


// Class for solving the Match the Pair problem in the 2014 ASIS Cyber Security
// Contest
//
// Sends and processes requests to the Match the Pair problem site and
// processes each question. Processing happens by first downloading all 16
// images corresponding to each square then performing a circle detection
// algorithm to find the color of the circle. Once that is done, compare the
// color to the other squares to find which ones match. Submit the answer as an
// HTTP GET request and output the server's response until we are done, 40
// problems later.
//
// Due to the nature of the image globbing algorithm you need to run this
// program with additional stack space. I found `-Xss64m` was enough to keep it
// from crashing.
public class MatchThePair {
    // URL to the challenge website
    public static final String BASE_URL = "http://asis-ctf.ir:12443";

    // debugging with timestamps (measured in seconds from problem start)
    private static long problemStartTime;
    public static void debug(String message) {
        System.out.printf("[%2.3f] %s\n", (System.currentTimeMillis() - problemStartTime) / 1000.0, message);
    }

    public static void main(String[] args) throws IOException {
        // parallelism makes this run a ton faster
        ExecutorService threadService = Executors.newFixedThreadPool(16);

        // the problem explains that we have to go through 40 levels
        for (int levelNumber = 1; levelNumber <= 40; levelNumber++) {
            // VERY IMPORTANT: need to "visit" the index page before each level
            // or else bad things happen and you can't get past level #2
            downloadFile(new URL(BASE_URL));

            // save start time for debugging purposes
            problemStartTime = System.currentTimeMillis();
            debug("Playing level #" + levelNumber);

            // download the 16 images and save the color of their circle
            @SuppressWarnings("unchecked")
            Future<Integer>[] colorFutures = new Future[16];
            int[] colors = new int[16];
            for (int i = 0; i < 16; i++) {
                final int index = i;
                colorFutures[i] = threadService.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        BufferedImage img = downloadImage(index);
                        int color = findCircleColor(img);
                        if (color == 0) {
                            debug("ERROR: Failed to detect color for id=" + index);
                            System.exit(1);
                        }
                        return color;
                    }
                });

            }

            // wait for all the image downloads to finish
            // as the images come in, try to find matches and submit the
            // results
            List<Future<String>> resultFutures = new ArrayList<>();
            while (resultFutures.size() < 8) { // 16 images = 8 matches
                try { Thread.sleep(1); } catch (Throwable t) { }
                for (int i = 0; i < colorFutures.length; i++) {
                    Future<Integer> future = colorFutures[i];

                    // null means it's already been processed
                    if (future == null) continue;

                    // just recently finished, try looking for a match
                    if (future.isDone()) {
                        try {
                            colors[i] = future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }

                        //System.out.printf("Image #%d color: %08x\n", i + 1, color);
                        colorFutures[i] = null;
                        resultFutures.addAll(lookForMatches(colors, threadService));
                    }
                }
            }

            // ensure that we found a match for each color
            boolean unmatched = false;
            for (int i = 0; i < colors.length; i++) {
                if (colors[i] != 0) {
                    System.out.println("ERROR: Did not match " + (i + 1));
                    unmatched = true;
                }
            }
            if (unmatched) System.exit(1);

            // wait for all the result submissions to finish before moving on
            // to the next problem
            boolean waiting = true;
            while (waiting) {
                try { Thread.sleep(1); } catch (Throwable t) { }
                waiting = false;
                for (Future<String> future : resultFutures) {
                    if (!future.isDone()) {
                        waiting = true;
                        break;
                    }
                }
            }
        }
    }

    // search for matching colors in the colors array
    // if found, submit a try and return the future
    private static List<Future<String>> lookForMatches(int[] colors, ExecutorService executor) {
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            // zero means this color is not available
            if (colors[i] == 0) continue;
            // look later in the list for possible matches
            for (int j = i + 1; j < colors.length; j++) {
                // zero means this color is not available
                if (colors[j] == 0) continue;
                int c1 = colors[i];
                int c2 = colors[j];
                // check if c1 and c2 are close enough in the three different
                // colors
                if (closeEnough(c1, c2, 16) && closeEnough(c1, c2, 8) && closeEnough(c1, c2, 0)) {
                    debug("Matched " + (i + 1) + " and " + (j + 1));
                    colors[i] = 0;
                    colors[j] = 0;
                    final int ic = i;
                    final int jc = j;
                    // submit the request asynchronously
                    futures.add(executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            String result = submit(ic, jc);
                            debug("Result for " + ic + " and " + jc + ": " + result);
                            // something failed (e means wrong answer, slow
                            // means out of time)
                            if (result.equals("\"e\"") || result.equals("\"slow\"")) System.exit(1);
                            return result;
                        }
                    }));
                    break;
                }
            }
        }
        return futures;
    }

    // checks whether the two colors are "close enough" in value for the given
    // shift
    // shift of 16 is red, 8 is green, 0 is blue
    private static boolean closeEnough(int c1, int c2, int shift) {
        int p1 = (c1 >> shift) & 0xff;
        int p2 = (c2 >> shift) & 0xff;
        return Math.abs(p1 - p2) < 50;
    }

    // downloads the image behind the given id
    public static BufferedImage downloadImage(int id) {
        try {
            URL url = new URL(BASE_URL + "/pic/" + id);
            BufferedImage img =  ImageIO.read(downloadFile(url));
            //debugImage("pic", img);
            return img;
        } catch (IOException e) {
            System.out.println("Failed to download image id: " + id);
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    // returns an input stream used to read the given URL
    public static InputStream downloadFile(URL url) {
        try {
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            // not sure if User-Agent is required but cookie definitely is
            // otherwise you'll just get black circles that don't correspond to
            // the problem for every id
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/37.0.2062.124 Safari/537.36");
            con.setRequestProperty("Cookie", "sessionid=o4vshsd1ac158q9xz1txni9o06xkc2jz; PHPSESSID=tt5bifrgddqkts7ddu43tkdhq5; _pk_id.8.9163=2e974504fd83890c.1412971726.12.1413150728.1413147514.; csrftoken=LBCcpn8rh8Rz04PEJxt7KCevc7LihNMp");
            InputStream result = con.getInputStream();
            return result;
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    // submits a "guess" (which will hopefully always be correct)
    // first and second are image ids in range 0-15
    public static String submit(int first, int second) {
        try {
            URL url = new URL(BASE_URL + "/send?first=" + first + "&second=" + second);
            InputStream result = downloadFile(url);
            return new Scanner(result).useDelimiter("\\A").next();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    // finds the color of the circle located in the given image
    // returns 0 if no color could be found
    public static int findCircleColor(BufferedImage image) {
        //System.out.println(image.getWidth() + " x " + image.getHeight());
        // first find all the different colors in the image and create new
        // arrays of pixels with all other values "masked out" with 0's
        Map<Integer, int[][]> maskedPixels = new HashMap<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int[][] pixels = maskedPixels.get(pixel);
                if (pixels == null) {
                    // first time seeing this color, create new image
                    pixels = new int[image.getHeight()][image.getWidth()];
                    maskedPixels.put(pixel, pixels);
                }
                pixels[y][x] = pixel;
            }
        }

        // go through each of the masks and use `hasCircle` to see if it is
        // the color with a circle
        int circlePixel = 0;
        for (Map.Entry<Integer, int[][]> maskEntry : maskedPixels.entrySet()) {
            int pixel = maskEntry.getKey();
            int[][] pixels = maskEntry.getValue();
            //System.out.println(String.format("%08x", pixel));
            if (hasCircle(pixels)) {
                circlePixel = pixel;
                break;
            }
        }

        return circlePixel;
    }

    // determines if the given pixel array has a circle shape
    // the idea is to pull out contiguous blocks of pixels and crop the result
    // so that we can see if it forms something that looks like a circle
    public static boolean hasCircle(int[][] maskedPixels) {
        //debugImage("masked", maskedPixels);
        // as we loop through all the pixels look for a pixel that actually has
        // a value. calling `getCroppedPixels` will zero out any pixels that
        // are part of that glob, leaving us with just the pixels that are left
        for (int y = 0; y < maskedPixels.length; y++) {
            for (int x = 0; x < maskedPixels[0].length; x++) {
                int pixel = maskedPixels[y][x];
                // zero indicates it's not part of the pixels we are looking
                // for
                if (pixel != 0) {
                    // set of points that are a part of the contiguous region,
                    // filled in by `getCroppedPixels`
                    Set<Point> foundPoints = new HashSet<>();
                    tryBranch(x, y, maskedPixels, foundPoints);
                    //System.out.println("Found: " + foundPoints.size() + " pixels");
                    int[][] cropped = getCroppedPixels(pixel, maskedPixels, foundPoints);
                    if (isCircle(cropped)) {
                        debugImage("winner", cropped);
                        return true;
                    }
                }
            }
        }
        // no circles found
        return false;
    }

    // checks if the given point is a valid pixel and adds it to our list of
    // found points
    // if it's not already there then try branching up, down, left, and right
    private static void tryBranch(int x, int y, int[][] pixels, Set<Point> foundPoints) {
        // check if in bounds
        if (0 <= x && x < pixels[0].length && 0 <= y && y < pixels.length) {
            // check if valid pixel
            if (pixels[y][x] != 0) {
                Point point = new Point(x, y);
                // a result of true means that we haven't seen this pixel
                // before, try adjacent pixels then
                if (foundPoints.add(point)) {
                    helper(x, y, pixels, foundPoints);
                }
            }
        }
    }

    // function for trying adjacent pixels with `tryBranch`
    private static void helper(int x, int y, int[][] pixels, Set<Point> foundPoints) {
        tryBranch(x, y - 1, pixels, foundPoints); // try up
        tryBranch(x + 1, y, pixels, foundPoints); // try right
        tryBranch(x, y + 1, pixels, foundPoints); // try down
        tryBranch(x - 1, y, pixels, foundPoints); // try left
    }

    // crops a given set set of pixels to a smaller pixel matrix where there
    // will be no exterior rows or columns without one of our pixels
    // as we do that zero out pixels in the original matrix
    public static int[][] getCroppedPixels(int pixel, int[][] originalPixels, Set<Point> foundPoints) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Point p : foundPoints) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }

        int[][] pixels = new int[maxY - minY + 1][maxX - minX + 1];
        for (Point p : foundPoints) {
            pixels[p.y - minY][p.x - minX] = pixel;
            originalPixels[p.y][p.x] = 0;
        }

        return pixels;
    }

    public static final double ERROR_PERCENTAGE = 0.05;

    // checks if the given cropped pixel matrix resembles a circle
    // will match if only ERROR_PERCENTAGE pixels do not match our calculated
    // circle given the size of the region
    public static boolean isCircle(int[][] cropped) {
        //debugImage("isCircle", cropped);
        int diameter = Math.min(cropped.length, cropped[0].length);
        // ignore small circles which are usually artifacts
        if (diameter < 11) return false;
        double radiusSq = diameter * diameter / 4.0;
        double centerX = (double)cropped[0].length / 2;
        double centerY = (double)cropped.length / 2;
        int size = cropped.length * cropped[0].length;
        int errors = 0;
        for (int y = 0; y < cropped.length; y++) {
            for (int x = 0; x < cropped[0].length; x++) {
                boolean hasPixel = cropped[y][x] != 0;
                boolean inCircle = (Math.pow(centerX - (x + 0.5), 2) + Math.pow(centerY - (y + 0.5), 2) <= radiusSq);
                if ((inCircle && !hasPixel) || (!inCircle && hasPixel)) {
                    // found an error
                    errors++;
                    // check if we have exceeded our allowable errors
                    if ((double)errors / size > ERROR_PERCENTAGE) return false;
                }
            }
        }

        //System.out.println("Errors: " + errors + ", Size: " + size);
        return true;
    }

    // the following is debug code that was used to test the circle recognition
    // algorithm

    private static void debugImage(String name, int[][] rgbPixels) {
        BufferedImage img = new BufferedImage(rgbPixels[0].length, rgbPixels.length, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < rgbPixels.length; y++) {
            for (int x = 0; x < rgbPixels[0].length; x++) {
                img.setRGB(x, y, rgbPixels[y][x]);
            }
        }
        debugImage(name, img);
    }

    private static final Map<String, Integer> debugFileNames = new HashMap<>();
    private static void debugImage(final String name, BufferedImage img) {
        Integer num = debugFileNames.get(name);
        if (num == null || num.equals(16)) {
            File[] files = new File(".").listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return pathname.isFile() && pathname.getName().startsWith(name) && pathname.getName().endsWith(".png");
                }
            });
            for (File file : files) {
                file.delete();
            }
            num = 1;
        } else {
            num++;
        }
        debugFileNames.put(name, num);
        try {
            ImageIO.write(img, "PNG", new File(name + num + ".png"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
