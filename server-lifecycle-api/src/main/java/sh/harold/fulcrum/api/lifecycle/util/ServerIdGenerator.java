package sh.harold.fulcrum.api.lifecycle.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for generating server IDs.
 */
public class ServerIdGenerator {
    private static final Pattern STATIC_ID_PATTERN = Pattern.compile("^([a-z]+)(\\d+)$");
    private static final Pattern DYNAMIC_ID_PATTERN = Pattern.compile("^dynamic(\\d+)([A-Z])$");

    /**
     * Generates the next available server ID for a family.
     */
    public static String generateId(String family, Set<String> existingIds, boolean isDynamic) {
        if (isDynamic) {
            return generateDynamicId(existingIds);
        }
        return generateStaticId(family, existingIds);
    }

    private static String generateStaticId(String family, Set<String> existingIds) {
        int maxNumber = 0;
        
        for (String id : existingIds) {
            Matcher matcher = STATIC_ID_PATTERN.matcher(id);
            if (matcher.matches() && matcher.group(1).equals(family)) {
                int number = Integer.parseInt(matcher.group(2));
                maxNumber = Math.max(maxNumber, number);
            }
        }
        
        return family + (maxNumber + 1);
    }

    private static String generateDynamicId(Set<String> existingIds) {
        int maxNumber = 0;
        char maxLetter = 'A';
        
        for (String id : existingIds) {
            Matcher matcher = DYNAMIC_ID_PATTERN.matcher(id);
            if (matcher.matches()) {
                int number = Integer.parseInt(matcher.group(1));
                char letter = matcher.group(2).charAt(0);
                
                if (number > maxNumber || (number == maxNumber && letter > maxLetter)) {
                    maxNumber = number;
                    maxLetter = letter;
                }
            }
        }
        
        // Increment letter, or if at 'Z', increment number and reset to 'A'
        if (maxLetter == 'Z') {
            return "dynamic" + (maxNumber + 1) + "A";
        } else {
            return "dynamic" + maxNumber + (char)(maxLetter + 1);
        }
    }

    /**
     * Checks if an ID matches the expected format.
     */
    public static boolean isValidId(String id) {
        return STATIC_ID_PATTERN.matcher(id).matches() || 
               DYNAMIC_ID_PATTERN.matcher(id).matches();
    }

    /**
     * Extracts the family from a server ID.
     */
    public static String extractFamily(String id) {
        Matcher staticMatcher = STATIC_ID_PATTERN.matcher(id);
        if (staticMatcher.matches()) {
            return staticMatcher.group(1);
        }
        
        if (DYNAMIC_ID_PATTERN.matcher(id).matches()) {
            return "dynamic";
        }
        
        return null;
    }
}