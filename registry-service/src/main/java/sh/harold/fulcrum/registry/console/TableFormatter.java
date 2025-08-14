package sh.harold.fulcrum.registry.console;

import java.util.ArrayList;
import java.util.List;

/**
 * ASCII table formatter for console output
 */
public class TableFormatter {
    
    private final List<String> headers = new ArrayList<>();
    private final List<Integer> columnWidths = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();
    
    /**
     * Add headers to the table
     * @param headers The column headers
     * @return This formatter for chaining
     */
    public TableFormatter addHeaders(String... headers) {
        for (String header : headers) {
            this.headers.add(header);
            this.columnWidths.add(header.length());
        }
        return this;
    }
    
    /**
     * Add a row to the table
     * @param values The row values
     * @return This formatter for chaining
     */
    public TableFormatter addRow(String... values) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < values.length && i < headers.size(); i++) {
            String value = values[i] != null ? values[i] : "";
            row.add(value);
            // Update column width if needed
            if (value.length() > columnWidths.get(i)) {
                columnWidths.set(i, value.length());
            }
        }
        rows.add(row);
        return this;
    }
    
    /**
     * Build the formatted table
     * @return The formatted table as a string
     */
    public String build() {
        StringBuilder sb = new StringBuilder();
        
        // Calculate total width
        int totalWidth = columnWidths.stream().mapToInt(Integer::intValue).sum() 
                        + (columnWidths.size() - 1) * 3 + 4; // 3 for " | " between columns, 4 for "| " and " |"
        
        // Top border
        sb.append("┌").append("─".repeat(totalWidth - 2)).append("┐\n");
        
        // Headers
        sb.append("│ ");
        for (int i = 0; i < headers.size(); i++) {
            sb.append(padRight(headers.get(i), columnWidths.get(i)));
            if (i < headers.size() - 1) {
                sb.append(" │ ");
            }
        }
        sb.append(" │\n");
        
        // Header separator
        sb.append("├").append("─".repeat(totalWidth - 2)).append("┤\n");
        
        // Rows
        for (List<String> row : rows) {
            sb.append("│ ");
            for (int i = 0; i < row.size(); i++) {
                sb.append(padRight(row.get(i), columnWidths.get(i)));
                if (i < row.size() - 1) {
                    sb.append(" │ ");
                }
            }
            sb.append(" │\n");
        }
        
        // Bottom border
        sb.append("└").append("─".repeat(totalWidth - 2)).append("┘");
        
        return sb.toString();
    }
    
    private String padRight(String text, int length) {
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return text + " ".repeat(length - text.length());
    }
    
    /**
     * Format with color codes
     * @param text The text to color
     * @param color The ANSI color code
     * @return The colored text
     */
    public static String color(String text, String color) {
        return color + text + "\u001B[0m";
    }
    
    // ANSI color codes
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_RED = "\u001B[91m";
}