package com.fintrack.vault.parser;

import com.fintrack.common.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvStatementParserTest {

    private final CsvStatementParser parser = new CsvStatementParser();

    private List<ParsedStatementRow> parse(String csv) throws IOException {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parse_singleAmountColumn_positiveIsIncome() throws IOException {
        String csv = "Date,Description,Amount\n2024-01-10,Salary,5000.00\n";
        List<ParsedStatementRow> rows = parse(csv);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).type()).isEqualTo(TransactionType.INCOME);
        assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2024, 1, 10));
    }

    @Test
    void parse_singleAmountColumn_negativeIsExpense() throws IOException {
        String csv = "Date,Description,Amount\n2024-01-12,Coffee,-4.50\n";
        List<ParsedStatementRow> rows = parse(csv);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("4.50"));
    }

    @Test
    void parse_debitCreditColumns_identifiesCorrectType() throws IOException {
        String csv = "Date,Description,Debit,Credit\n" +
                "2024-01-15,Supermarket,45.00,\n" +
                "2024-01-16,Transfer,,200.00\n";
        List<ParsedStatementRow> rows = parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("45.00");
        assertThat(rows.get(1).type()).isEqualTo(TransactionType.INCOME);
        assertThat(rows.get(1).amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void parse_malformedRow_isSkipped() throws IOException {
        String csv = "Date,Description,Amount\n" +
                "2024-01-10,Good row,100.00\n" +
                "not-a-date,Bad row,???\n" +
                "2024-01-12,Another good row,-20.00\n";
        List<ParsedStatementRow> rows = parse(csv);

        // Malformed row is silently skipped; other rows are parsed
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).description()).isEqualTo("Good row");
        assertThat(rows.get(1).description()).isEqualTo("Another good row");
    }

    @Test
    void parse_commasInAmount_parsedCorrectly() throws IOException {
        String csv = "Date,Description,Amount\n2024-03-01,Big payment,\"1,500.00\"\n";
        List<ParsedStatementRow> rows = parse(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void parse_emptyInput_returnsEmptyList() throws IOException {
        List<ParsedStatementRow> rows = parse("Date,Description,Amount\n");
        assertThat(rows).isEmpty();
    }
}
