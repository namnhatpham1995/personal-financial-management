package com.fintrack.vault.parser;

import com.fintrack.common.domain.TransactionType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a bank-exported CSV file into a list of {@link ParsedStatementRow}.
 *
 * Accepts two common column layouts:
 *   (a) Date, Description, Amount        — positive = credit, negative = debit
 *   (b) Date, Description, Debit, Credit — separate debit/credit columns
 *
 * Rows that cannot be parsed are silently skipped so a partially corrupt export
 * still yields the good rows.
 */
@Component
public class CsvStatementParser {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    public List<ParsedStatementRow> parse(InputStream input) throws IOException {
        var parser = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(input, StandardCharsets.UTF_8));

        List<String> headerNames = parser.getHeaderNames();
        int categoryColumnIndex = findColumnIndex(headerNames, "category");
        // A trailing optional category column also pushes the column count to 4+ for the
        // single-Amount layout, so the debit/credit layout must be detected by header name
        // rather than raw column count.
        boolean debitCreditLayout = findColumnIndex(headerNames, "debit") >= 0
                && findColumnIndex(headerNames, "credit") >= 0;

        List<ParsedStatementRow> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            try {
                rows.add(toRow(record, categoryColumnIndex, debitCreditLayout));
            } catch (Exception ignored) {
                // Skip unparseable rows
            }
        }
        return rows;
    }

    /** Case-insensitive match for a header with the given name; returns -1 when absent. */
    private int findColumnIndex(List<String> headerNames, String name) {
        for (int i = 0; i < headerNames.size(); i++) {
            if (name.equalsIgnoreCase(headerNames.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private ParsedStatementRow toRow(CSVRecord rec, int categoryColumnIndex, boolean debitCreditLayout) {
        LocalDate date = parseDate(rec.get(0));
        String description = rec.get(1);
        BigDecimal amount;
        TransactionType type;

        if (debitCreditLayout) {
            // Layout (b): Debit, Credit columns
            String debitStr = rec.get(2).trim();
            String creditStr = rec.get(3).trim();
            if (!creditStr.isEmpty() && !creditStr.equals("0") && !creditStr.equals("0.00")) {
                amount = new BigDecimal(creditStr.replace(",", ""));
                type = TransactionType.INCOME;
            } else {
                amount = new BigDecimal(debitStr.replace(",", ""));
                type = TransactionType.EXPENSE;
            }
        } else {
            // Layout (a): single signed Amount column
            BigDecimal raw = new BigDecimal(rec.get(2).replace(",", "").replace(" ", ""));
            if (raw.compareTo(BigDecimal.ZERO) >= 0) {
                amount = raw;
                type = TransactionType.INCOME;
            } else {
                amount = raw.negate();
                type = TransactionType.EXPENSE;
            }
        }

        String category = extractCategory(rec, categoryColumnIndex);
        return new ParsedStatementRow(date, amount, type, description, rec.toString(), null, category);
    }

    /** Tolerant of the column being absent from this row's data (short row) — returns null. */
    private String extractCategory(CSVRecord rec, int categoryColumnIndex) {
        if (categoryColumnIndex < 0 || categoryColumnIndex >= rec.size()) {
            return null;
        }
        String value = rec.get(categoryColumnIndex).trim();
        return value.isEmpty() ? null : value;
    }

    private LocalDate parseDate(String raw) {
        String cleaned = raw.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        throw new IllegalArgumentException("Unrecognized date format: " + raw);
    }
}
