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
        var records = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(input, StandardCharsets.UTF_8));

        List<ParsedStatementRow> rows = new ArrayList<>();
        for (CSVRecord record : records) {
            try {
                rows.add(toRow(record));
            } catch (Exception ignored) {
                // Skip unparseable rows
            }
        }
        return rows;
    }

    private ParsedStatementRow toRow(CSVRecord rec) {
        LocalDate date = parseDate(rec.get(0));
        String description = rec.get(1);
        BigDecimal amount;
        TransactionType type;

        if (rec.size() >= 4) {
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

        return new ParsedStatementRow(date, amount, type, description, rec.toString());
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
