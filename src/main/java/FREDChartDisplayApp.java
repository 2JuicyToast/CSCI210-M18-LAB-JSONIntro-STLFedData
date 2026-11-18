import com.fasterxml.jackson.databind.ObjectMapper;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * FREDChartDisplayApp
 *
 * A Swing application that integrates with the Federal Reserve Economic Data (FRED) API
 * and local data storage. It fetches economic time series data, stores observations in a
 * Java TreeMap, outputs formatted JSON and CSV datasets, and visualizes the results
 * using JFreeChart.
 */
public class FREDChartDisplayApp extends JFrame {

    // FRED API base URL and endpoints
    private static final String FRED_BASE_URL = "https://api.stlouisfed.org/fred";
    private static final String FRED_SERIES_ENDPOINT = "https://api.stlouisfed.org/fred/series";
    private static final String FRED_OBSERVATIONS_ENDPOINT = "https://api.stlouisfed.org/fred/series/observations";

    // Required data file storage directories
    private static final String JSON_DIR = "datafiles/FREDData-JSON";
    private static final String CSV_DIR = "datafiles/FREDData-CSV";

    private final JPanel chartContainer;
    private final JLabel statusLabel;
    private final JLabel infoLabel;

    /**
     * Initializes the FRED Chart Display GUI application.
     */
    public FREDChartDisplayApp() {
        super("FRED Economic Data Chart Display");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);

        // Construct menu bar (JMenuBar / JMenuItem)
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        // Menu Option 1: Fetch FRED Data
        JMenuItem fetchFredDataItem = new JMenuItem("Fetch FRED Data");
        fetchFredDataItem.addActionListener(e -> promptAndFetchFREDData());
        fileMenu.add(fetchFredDataItem);

        // Menu Option 2: Display FRED Data from Saved File
        JMenuItem displaySavedFileItem = new JMenuItem("Display FRED Data from Saved File");
        displaySavedFileItem.addActionListener(e -> chooseAndDisplaySavedFile());
        fileMenu.add(displaySavedFileItem);

        fileMenu.addSeparator();

        // Menu Option 3: Exit
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // Main layout components
        setLayout(new BorderLayout());

        // Header series metadata label
        infoLabel = new JLabel(" Select 'Fetch FRED Data' or 'Display FRED Data from Saved File' to view charts.", SwingConstants.CENTER);
        infoLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        infoLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        add(infoLabel, BorderLayout.NORTH);

        // Center chart container panel
        chartContainer = new JPanel(new BorderLayout());
        chartContainer.setBackground(Color.WHITE);
        JLabel placeholder = new JLabel("No data loaded. Use the File menu to fetch or display time series data.", SwingConstants.CENTER);
        placeholder.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chartContainer.add(placeholder, BorderLayout.CENTER);
        add(chartContainer, BorderLayout.CENTER);

        // Bottom status bar
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                new EmptyBorder(5, 8, 5, 8)
        ));
        add(statusLabel, BorderLayout.SOUTH);

        // Pre-load sample data if present without blocking modal dialogs
        File sampleJson = new File(JSON_DIR, "PCE.json");
        if (sampleJson.exists()) {
            try {
                loadAndDisplayJSONFile(sampleJson);
            } catch (Exception ex) {
                statusLabel.setText(" Ready (Sample PCE.json available)");
            }
        }
    }

    /**
     * Prompts the user for a FRED series symbol and optional API key, then initiates fetching.
     */
    private void promptAndFetchFREDData() {
        String symbol = JOptionPane.showInputDialog(
                this,
                "Enter FRED Series ID (e.g., PCE, GDP, UNRATE, CPIAUCSL):",
                "Fetch FRED Data",
                JOptionPane.QUESTION_MESSAGE
        );
        if (symbol == null || symbol.trim().isEmpty()) {
            return;
        }
        final String seriesSymbol = symbol.trim().toUpperCase();

        String envApiKey = System.getenv("FRED_API_KEY");
        String apiKey = envApiKey;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = JOptionPane.showInputDialog(
                    this,
                    "Enter your FRED API Key (leave empty if checking local cache):",
                    "FRED API Key",
                    JOptionPane.QUESTION_MESSAGE
            );
        }
        final String finalApiKey = apiKey != null ? apiKey.trim() : "";

        statusLabel.setText(" Fetching data for " + seriesSymbol + " from FRED API...");

        new Thread(() -> {
            try {
                fetchAndProcessFREDData(seriesSymbol, finalApiKey);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(" Error fetching data: " + ex.getMessage());
                    File fallback = new File(JSON_DIR, seriesSymbol + ".json");
                    if (fallback.exists()) {
                        int opt = JOptionPane.showConfirmDialog(
                                this,
                                "Failed to fetch from FRED API (" + ex.getMessage() + ").\n" +
                                        "Found local cached file: " + fallback.getPath() + "\n" +
                                        "Would you like to display the local cached file?",
                                "Load Local Data?",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                        );
                        if (opt == JOptionPane.YES_OPTION) {
                            try {
                                loadAndDisplayJSONFile(fallback);
                            } catch (Exception loadEx) {
                                JOptionPane.showMessageDialog(this, "Error loading local file: " + loadEx.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                                this,
                                "Failed to fetch FRED data: " + ex.getMessage() + "\n\n" +
                                        "Note: Querying api.stlouisfed.org/fred requires a valid API key.\n" +
                                        "Get one for free at https://fred.stlouisfed.org/docs/api/api_key.html\n" +
                                        "Or use 'Display FRED Data from Saved File' to view local JSON datasets (such as PCE.json).",
                                "API Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                });
            }
        }).start();
    }

    /**
     * Queries FRED API series and observations endpoints, merges them, saves JSON via Jackson,
     * stores observation records in a TreeMap, and writes formatted CSV output.
     */
    public void fetchAndProcessFREDData(String symbol, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("FRED API key is required to query api.stlouisfed.org/fred endpoints.");
        }

        // 1. Query series endpoint
        String seriesUrl = FRED_SERIES_ENDPOINT + "?series_id=" + symbol + "&api_key=" + apiKey + "&file_type=json";
        String seriesResponse = fetchUrl(seriesUrl);
        JSONObject seriesRoot = new JSONObject(seriesResponse);

        // 2. Query observations endpoint
        String obsUrl = FRED_OBSERVATIONS_ENDPOINT + "?series_id=" + symbol + "&api_key=" + apiKey + "&file_type=json";
        String obsResponse = fetchUrl(obsUrl);
        JSONObject obsRoot = new JSONObject(obsResponse);

        JSONArray observationsArray = obsRoot.optJSONArray("observations");
        if (observationsArray == null) {
            observationsArray = new JSONArray();
        }

        // 3. Merge observations array into series metadata structure
        if (seriesRoot.has("seriess") && seriesRoot.getJSONArray("seriess").length() > 0) {
            JSONObject sObj = seriesRoot.getJSONArray("seriess").getJSONObject(0);
            sObj.put("observations", observationsArray);
        } else {
            seriesRoot.put("observations", observationsArray);
        }

        // 4. Format JSON with Jackson ObjectMapper for pretty-printed output
        ObjectMapper objectMapper = new ObjectMapper();
        Object jsonTree = objectMapper.readValue(seriesRoot.toString(), Object.class);
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonTree);

        // 5. Save to datafiles/FREDData-JSON directory
        File jsonDir = new File(JSON_DIR);
        if (!jsonDir.exists()) {
            jsonDir.mkdirs();
        }
        File jsonFile = new File(jsonDir, symbol + ".json");
        Files.writeString(jsonFile.toPath(), prettyJson, StandardCharsets.UTF_8);

        // 6. Process observations into TreeMap and save to datafiles/FREDData-CSV directory
        TreeMap<String, Double> observationsMap = parseObservationsToTreeMap(seriesRoot);
        saveObservationsToCSV(symbol, observationsMap);

        // 7. Update UI on Swing Event Dispatch Thread
        String title = symbol;
        String units = "Value";
        if (seriesRoot.has("seriess") && seriesRoot.getJSONArray("seriess").length() > 0) {
            JSONObject sObj = seriesRoot.getJSONArray("seriess").getJSONObject(0);
            title = sObj.optString("title", symbol);
            units = sObj.optString("units", "Value");
        }

        final String finalTitle = title;
        final String finalUnits = units;

        SwingUtilities.invokeLater(() -> {
            displayChart(finalTitle, finalUnits, observationsMap);
            infoLabel.setText(String.format("<html><b>%s (%s)</b> &nbsp;|&nbsp; Units: %s &nbsp;|&nbsp; Observations: %d &nbsp;|&nbsp; Range: %s to %s</html>",
                    finalTitle, symbol, finalUnits, observationsMap.size(),
                    observationsMap.isEmpty() ? "N/A" : observationsMap.firstKey(),
                    observationsMap.isEmpty() ? "N/A" : observationsMap.lastKey()));
            statusLabel.setText(String.format(" Successfully fetched %s (%d observations). Saved JSON and CSV.",
                    symbol, observationsMap.size()));
            JOptionPane.showMessageDialog(
                    this,
                    "Data successfully fetched for " + symbol + " (" + observationsMap.size() + " observations).\n" +
                            "Saved to:\n- " + jsonFile.getPath() + "\n- " + CSV_DIR + "/" + symbol + ".csv",
                    "Fetch Complete",
                    JOptionPane.INFORMATION_MESSAGE
            );
        });
    }

    /**
     * Opens a JFileChooser dialog allowing the user to select a saved FRED JSON file.
     */
    private void chooseAndDisplaySavedFile() {
        JFileChooser fileChooser = new JFileChooser(new File(JSON_DIR));
        fileChooser.setDialogTitle("Select FRED JSON File");
        fileChooser.setFileFilter(new FileNameExtensionFilter("JSON Files (*.json)", "json"));

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                loadAndDisplayJSONFile(selectedFile);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Failed to open/display JSON file:\n" + ex.getMessage(),
                        "File Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    /**
     * Reads a saved FRED JSON file, parses observations into a TreeMap, saves CSV, and displays the chart.
     */
    public void loadAndDisplayJSONFile(File file) throws IOException {
        String jsonContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(jsonContent);

        String symbol = file.getName().replace(".json", "");
        String title = symbol;
        String units = "Value";

        if (root.has("seriess") && root.getJSONArray("seriess").length() > 0) {
            JSONObject sObj = root.getJSONArray("seriess").getJSONObject(0);
            symbol = sObj.optString("id", symbol);
            title = sObj.optString("title", symbol);
            units = sObj.optString("units", "Value");
        }

        TreeMap<String, Double> observationsMap = parseObservationsToTreeMap(root);

        // Ensure CSV file exists in datafiles/FREDData-CSV
        saveObservationsToCSV(symbol, observationsMap);

        final String finalSymbol = symbol;
        final String finalTitle = title;
        final String finalUnits = units;

        SwingUtilities.invokeLater(() -> {
            displayChart(finalTitle, finalUnits, observationsMap);
            infoLabel.setText(String.format("<html><b>%s (%s)</b> &nbsp;|&nbsp; Units: %s &nbsp;|&nbsp; Observations: %d &nbsp;|&nbsp; Range: %s to %s</html>",
                    finalTitle, finalSymbol, finalUnits, observationsMap.size(),
                    observationsMap.isEmpty() ? "N/A" : observationsMap.firstKey(),
                    observationsMap.isEmpty() ? "N/A" : observationsMap.lastKey()));
            statusLabel.setText(String.format(" Loaded %s (%d observations). Exported %s/%s.csv",
                    file.getName(), observationsMap.size(), CSV_DIR, finalSymbol));
        });
    }

    /**
     * Extracts date and numeric observation values into a TreeMap (sorted chronologically).
     */
    public static TreeMap<String, Double> parseObservationsToTreeMap(JSONObject root) {
        TreeMap<String, Double> observationsMap = new TreeMap<>();
        JSONArray obsArray = null;

        if (root.has("seriess") && root.getJSONArray("seriess").length() > 0) {
            JSONObject sObj = root.getJSONArray("seriess").getJSONObject(0);
            if (sObj.has("observations")) {
                obsArray = sObj.getJSONArray("observations");
            }
        }
        if (obsArray == null && root.has("observations")) {
            obsArray = root.getJSONArray("observations");
        }

        if (obsArray != null) {
            for (int i = 0; i < obsArray.length(); i++) {
                JSONObject obs = obsArray.getJSONObject(i);
                String date = obs.optString("date");
                String valStr = obs.optString("value");
                if (date != null && !date.isEmpty() && valStr != null && !valStr.equals(".")) {
                    try {
                        double val = Double.parseDouble(valStr);
                        observationsMap.put(date, val);
                    } catch (NumberFormatException ignored) {
                        // Skip unparseable or missing data points
                    }
                }
            }
        }
        return observationsMap;
    }

    /**
     * Saves observations to a CSV file in datafiles/FREDData-CSV with header 'Date,{symbol}_Value'.
     */
    public static void saveObservationsToCSV(String symbol, TreeMap<String, Double> observationsMap) throws IOException {
        File csvDir = new File(CSV_DIR);
        if (!csvDir.exists()) {
            csvDir.mkdirs();
        }
        File csvFile = new File(csvDir, symbol + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile.toPath(), StandardCharsets.UTF_8)) {
            // Required CSV header convention: Date,{symbol}_Value
            writer.write("Date," + symbol + "_Value\n");
            for (Map.Entry<String, Double> entry : observationsMap.entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue() + "\n");
            }
        }
    }

    /**
     * Displays a time series chart using JFreeChart inside the chart container panel.
     */
    private void displayChart(String title, String units, TreeMap<String, Double> observationsMap) {
        TimeSeries timeSeries = new TimeSeries(title);

        for (Map.Entry<String, Double> entry : observationsMap.entrySet()) {
            String dateStr = entry.getKey();
            try {
                String[] parts = dateStr.split("-");
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int day = Integer.parseInt(parts[2]);
                timeSeries.addOrUpdate(new Day(day, month, year), entry.getValue());
            } catch (Exception ignored) {
            }
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection(timeSeries);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title,
                "Date",
                units != null && !units.isEmpty() ? units : "Value",
                dataset,
                true,   // show legend
                true,   // show tooltips
                false   // no urls
        );

        // Styling chart elements
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(250, 250, 250));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        XYItemRenderer renderer = plot.getRenderer();
        renderer.setSeriesPaint(0, new Color(31, 119, 180));
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));

        DateAxis dateAxis = (DateAxis) plot.getDomainAxis();
        dateAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM"));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setDomainZoomable(true);
        chartPanel.setRangeZoomable(true);

        chartContainer.removeAll();
        chartContainer.add(chartPanel, BorderLayout.CENTER);
        chartContainer.revalidate();
        chartContainer.repaint();
    }

    /**
     * Helper method to perform HTTP GET requests.
     */
    private static String fetchUrl(String urlString) throws IOException {
        URI uri = URI.create(urlString);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            InputStream err = conn.getErrorStream();
            String errBody = err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "";
            throw new IOException("HTTP " + responseCode + " from " + urlString + ": " + errBody);
        }

        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FREDChartDisplayApp app = new FREDChartDisplayApp();
            app.setVisible(true);
        });
    }
}
