package greenebolt.autotrade;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.apache.logging.log4j.util.StringBuilders.escapeJson;

public class Config {
    private static final Properties defaultValues = new Properties();
    public static String fileName;

    public static String[] targetItems;

    Config(String fileName) {
        this.fileName = fileName;
    }

    public void read() {
        try {
            BufferedReader configReader = new BufferedReader(new FileReader(fileName));
            String json = Files.readString(Paths.get(fileName));
            configReader.close();

            int start = json.indexOf('[');
            int end = json.indexOf(']');

            if (start == -1 || end == -1 || end <= start) {
                throw new RuntimeException("Invalid JSON format");
            }

            String arrayContent = json.substring(start + 1, end);

            String[] rawItems = arrayContent.split(",");

            List<String> result = new ArrayList<>();

            for (String item : rawItems) {
                String cleaned = item.trim()
                        .replaceAll("^\"|\"$", ""); // remove surrounding quotes
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }

            targetItems = result.toArray(new String[0]);

        } catch (FileNotFoundException ignored) {
            // If the config does not exist, generate the default one.
            AutoTrade.LOGGER.info("Generating the config file at: " + fileName);
            generateConfig();
            return;
        } catch (IOException e) {
            AutoTrade.LOGGER.info("Failed to read the config file: " + fileName);
            e.printStackTrace();
        }

    }

    public static void save() {

        StringBuilder json = new StringBuilder();

        json.append("{\n  \"Trade Cost Targets:\": [\n");

        for (int i = 0; i < targetItems.length; i++) {
            json.append("    \"")
                    .append(targetItems[i])
                    .append("\"");

            if (i < targetItems.length - 1) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  ]\n}");

        try {
            File config = new File(fileName);
            File parentDir = config.getParentFile();
            if (!parentDir.exists())
                parentDir.mkdirs();

            FileWriter configWriter = new FileWriter(config);

            configWriter.flush();
            configWriter.write(json.toString());

            configWriter.close();
        } catch (IOException e) {
            AutoTrade.LOGGER.info("Failed to write the config file: " + fileName);
            e.printStackTrace();
        }
    }

    private void generateConfig() {
        try {
            File config = new File(fileName);
            File parentDir = config.getParentFile();
            if (!parentDir.exists())
                parentDir.mkdirs();
            FileWriter configWriter = new FileWriter(config);

            configWriter.write("{\n  \"Trade Cost Targets:\": [\n    \"iron_ingot\"\n  ]\n}");
            targetItems = new String[]{"iron_ingot"};

            configWriter.close();

        } catch (IOException e) {
            AutoTrade.LOGGER.info("Failed to generate config file: " + fileName);
            e.printStackTrace();
        }

    }
}
