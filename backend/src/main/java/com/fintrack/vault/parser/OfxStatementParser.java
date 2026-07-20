package com.fintrack.vault.parser;

import com.fintrack.common.domain.TransactionType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal OFX SGML parser — no external lib required.
 *
 * Handles the classic non-XML OFX 1.x SGML format produced by most retail banks:
 *   <STMTTRN>
 *     <TRNTYPE>DEBIT
 *     <DTPOSTED>20240115
 *     <TRNAMT>-45.00
 *     <MEMO>SUPERMARKET
 *   </STMTTRN>
 *
 * Does not support OFX 2.x (XML-based); those are rare in consumer exports.
 */
@Component
public class OfxStatementParser {

    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<ParsedStatementRow> parse(InputStream input) throws IOException {
        String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        List<ParsedStatementRow> rows = new ArrayList<>();

        // Extract each <STMTTRN>…</STMTTRN> block
        int start = 0;
        while (true) {
            int open = content.indexOf("<STMTTRN>", start);
            if (open < 0) break;
            int close = content.indexOf("</STMTTRN>", open);
            if (close < 0) break;
            String block = content.substring(open, close);
            try {
                rows.add(parseBlock(block));
            } catch (Exception ignored) {
                // Skip malformed transaction block
            }
            start = close + 1;
        }
        return rows;
    }

    private ParsedStatementRow parseBlock(String block) {
        String trnType = extract(block, "TRNTYPE");
        String dtPosted = extract(block, "DTPOSTED");
        String trnAmt = extract(block, "TRNAMT");
        String memo = extractOrDefault(block, "MEMO",
                extractOrDefault(block, "NAME", ""));
        // Real banks nearly always emit FITID, but treat it as optional — a blank/missing value
        // falls back to the occurrence-ordinal fingerprint in StatementImportService.
        String fitIdRaw = extractOrDefault(block, "FITID", "");
        String fitId = fitIdRaw.isBlank() ? null : fitIdRaw.trim();

        // OFX dates may be yyyyMMddHHmmss — truncate to 8 chars
        LocalDate date = LocalDate.parse(dtPosted.substring(0, 8), OFX_DATE);
        BigDecimal rawAmount = new BigDecimal(trnAmt.trim());

        BigDecimal amount;
        TransactionType type;

        if (rawAmount.compareTo(BigDecimal.ZERO) < 0) {
            amount = rawAmount.negate();
            type = TransactionType.EXPENSE;
        } else {
            amount = rawAmount;
            // OFX CREDIT or positive DEBIT — treat as income
            type = "CREDIT".equalsIgnoreCase(trnType) ? TransactionType.INCOME : TransactionType.INCOME;
        }

        return new ParsedStatementRow(date, amount, type, memo.trim(), block.trim(), fitId);
    }

    private String extract(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]+)").matcher(block);
        if (!m.find()) {
            throw new IllegalArgumentException("Missing tag <" + tag + ">");
        }
        return m.group(1).trim();
    }

    private String extractOrDefault(String block, String tag, String defaultValue) {
        Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]+)").matcher(block);
        return m.find() ? m.group(1).trim() : defaultValue;
    }
}
