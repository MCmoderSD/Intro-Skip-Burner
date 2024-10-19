import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

import java.math.BigDecimal;
import java.net.URISyntaxException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static java.nio.file.Files.write;
import static java.nio.file.StandardOpenOption.APPEND;

public class Converter {

    // Record
    public record Chapter(int begin, int end, boolean isIntro, boolean isOutro) {}

    // Extensions
    public static final String EDL = ".edl";
    public static final String VIDEO = ".mp4";
    public static final String FFMETA = ".ffmeta";

    // Constants
    public static final String HEADER = ";FFMETADATA1\n";
    public static final String INTRO = "Intro";
    public static final String OUTRO = "Outro";
    public static final String CONTENT = "Content";
    public static final String CHAPTER =
            """
            
            [CHAPTER]
            TIMEBASE=1/1000
            START=%d
            END=%d
            title=%s
            """;

    // Attributes
    private final HashMap<Integer, ArrayList<Chapter>> edlData;
    private final HashMap<Integer, Integer> videoLengths;

    // Constructor
    public Converter(String directory) throws IOException, InterruptedException {

        // Initialize Attributes
        edlData = new HashMap<>();
        videoLengths = new HashMap<>();

        // Scan EDL Files
        var start = System.nanoTime();
        scanEdlFiles(directory);
        var scanTime = System.nanoTime() - start;

        // Write FFMeta Files
        writeFFMetaFile(directory);
        var writeTime = System.nanoTime() - start - scanTime;

        // Append to FFMeta File
        appendFFMetaFile(directory);
        var appendTime = System.nanoTime() - start - scanTime - writeTime;

        // Debug
        System.out.println("\n\n\n");
        System.out.println("Scan Time: " + scanTime / 1_000_000 + "ms");
        System.out.println("Write Time: " + writeTime / 1_000_000 + "ms");
        System.out.println("Append Time: " + appendTime / 1_000_000 + "ms");
        System.out.println("Total Time: " + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    // Scan EDL Files
    private void scanEdlFiles(String path) throws IOException {

        // Get Files
        ArrayList<File> files = new ArrayList<>(List.of(Objects.requireNonNull(new File(path).listFiles())));
        files.removeIf(file -> !file.getName().endsWith(EDL));

        // Sort Files
        files.sort((f1, f2) -> {
            Integer f1Int = Integer.parseInt(f1.getName().replace(EDL, ""));
            Integer f2Int = Integer.parseInt(f2.getName().replace(EDL, ""));
            return f1Int.compareTo(f2Int);
        });

        // Iterate over all Files
        for (File file : files) if (file.isFile() && file.getName().endsWith(EDL)) {

            // Get File Name
            String fileName = file.getName().replace(EDL, "");
            Integer fileNameInt = Integer.parseInt(fileName);

            // Get Video Duration
            var videoLength = getVideoLength(path + fileName + VIDEO);
            videoLengths.put(fileNameInt, videoLength);

            // Debug
            System.out.println("Processing File: " + fileName);
            System.out.println("\tDuration: " + videoLength + "ms");
            System.out.println("\tChapters:");

            // Create ArrayList
            ArrayList<Chapter> chapters = new ArrayList<>();

            // Read File
            BufferedReader br = new BufferedReader(new FileReader(file));

            // Read Lines
            String line;
            var lineIndex = 0;
            while ((line = br.readLine()) != null) {

                // Count Line
                lineIndex++;

                // Split Line
                String[] split = line.split(" ");

                // Convert to Milliseconds
                var begin = new BigDecimal(split[0]).movePointRight(3).intValue();
                var end = new BigDecimal(split[1]).movePointRight(3).intValue();

                // Check if Intro or Outro
                if (lineIndex == 1 && videoLength / 2 > end) chapters.add(new Chapter(begin, end, true, false));
                else chapters.add(new Chapter(begin, end, false, true));

                // Debug
                System.out.println("\t\t" + begin + "ms - " + end + "ms" + (lineIndex == 1 && videoLength / 2 > end ? INTRO : OUTRO + "\n"));
            }

            // Add Chapters
            edlData.put(fileNameInt, chapters);
        }
    }

    // Write FFMeta File
    private void writeFFMetaFile(String path) throws IOException {

        // Iterate over all chapters
        for (var i : edlData.keySet()) {

            // Get Chapters
            ArrayList<Chapter> chapters = edlData.get(i);

            // Intro or Outro
            if (chapters.size() == 1) {

                // Get Chapter
                Chapter chapter = chapters.getFirst();

                // Create Skip Chapters
                Chapter skipChapter = new Chapter(chapter.end, videoLengths.get(i), false, false);

                // Create pre-Intro
                Chapter preChapter = new Chapter(0, chapter.begin, false, false);

                // Add Chapters
                chapters = new ArrayList<>();
                if (chapter.begin > 0) chapters.add(preChapter);
                chapters.add(chapter);
                chapters.add(skipChapter);
            }

            // Intro and Outro
            else if (chapters.size() == 2) {

                // Get Chapters
                Chapter intro = chapters.getFirst();
                Chapter outro = chapters.getLast();

                // Create Skip Chapters
                Chapter introSkip = new Chapter(intro.end, outro.begin, false, false);
                Chapter outroSkip = new Chapter(outro.end, videoLengths.get(i), false, false);

                // Create pre-Intro
                Chapter preIntro = new Chapter(0, intro.begin, false, false);

                // Add Chapters
                chapters = new ArrayList<>();
                if (intro.begin > 0) chapters.add(preIntro);
                chapters.add(intro);
                chapters.add(introSkip);
                chapters.add(outro);
                chapters.add(outroSkip);
            }

            // Create File
            var file = new File(path + i + FFMETA);

            // Write Header
            write(file.toPath(), HEADER.getBytes());

            // Write Chapters
            for (var chapter : chapters) write(file.toPath(), String.format(CHAPTER, chapter.begin, chapter.end, chapter.isIntro ? INTRO : chapter.isOutro ? OUTRO : CONTENT).getBytes(), APPEND);
        }
    }

    // Append to FFMeta File
    private void appendFFMetaFile(String path) throws IOException, InterruptedException {


        // Variables
        ArrayList<File> files = new ArrayList<>(List.of(Objects.requireNonNull(new File(path).listFiles())));
        files.removeIf(file -> !file.getName().endsWith(VIDEO));

        // Sort Files
        files.sort((f1, f2) -> {
            Integer f1Int = Integer.parseInt(f1.getName().replace(VIDEO, ""));
            Integer f2Int = Integer.parseInt(f2.getName().replace(VIDEO, ""));
            return f1Int.compareTo(f2Int);
        });

        // Apply Metadata
        for (File file : files) {

            // Variables
            var fileName = file.getName();
            var newFileName = "." + fileName;

            // Apply Metadata
            applyMetaData(path, file.getName());

            // Delete Old File
            boolean deleted = file.delete();

            // Rename New File
            boolean renamed = new File(path + newFileName).renameTo(new File(path + fileName));

            // Debug
            if (deleted && renamed) System.out.println("Successfully converted: " + fileName);
            else System.out.println("Failed to convert: " + fileName);
        }
    }

    // Get the length of a video file in milliseconds
    private static int getVideoLength(String filePath) {
        try {

            // Command to run ffprobe and get the duration in seconds
            String[] command = {
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    filePath
            };

            // Run ffprobe
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read the output of ffprobe
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String durationString = reader.readLine();
            process.waitFor();

            // Return the duration in milliseconds
            if (durationString != null) return new BigDecimal(durationString.trim()).movePointRight(3).intValue();
            else throw new IOException("Failed to get duration from ffprobe");

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get video duration: " + e.getMessage());
        }
    }

    // Apply metadata to a video file
    private static void applyMetaData(String path, String name) throws IOException, InterruptedException {

        // Get the file name and extension
        String fileName = name.substring(0, name.lastIndexOf('.'));
        String extension = name.substring(name.lastIndexOf('.') + 1);

        System.out.println("Applying metadata to: " + fileName + "." + extension);

        // Command to run ffmpeg and apply metadata
        String[] command = {
                "ffmpeg",
                "-i", path + name,
                "-i", path + fileName + ".ffmeta",
                "-map_metadata", "1",
                "-c:v", "copy",
                "-c:a", "copy",
                path + "." + fileName + "." + extension
        };

        // Run ffmpeg
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read the output of ffmpeg
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) System.out.println(line);
        process.waitFor();
    }

    // Main
    public static void main(String[] args) throws URISyntaxException, IOException, InterruptedException {

        // Variables
        String directory;

        // Get Directory
        if (args.length == 0) directory = new File(Converter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent() + "/";
        else directory = args[0];

        new Converter(directory);
    }
}