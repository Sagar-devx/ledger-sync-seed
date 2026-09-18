package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails (HDFC and ICICI).
 *
 * Email format:
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been debited with INR 99.99.
 *   Merchant / Remarks: IRCTC
 *   Transaction reference: 8085121323
 */
public final class EmailParser implements MessageParser {

    private static final Pattern ACCT = Pattern.compile("account ending (\\d{4})");
    private static final Pattern DIR = Pattern.compile("has been (debited|credited)");
    private static final Pattern MERCHANT = Pattern.compile("Merchant / Remarks:\\s*(.+)");
    private static final Pattern DATE_LINE = Pattern.compile("Date:\\s*(.+)");

    // RFC 2822 date: "Wed, 01 Jul 2026 09:02:00 +0530"
    private static final DateTimeFormatter RFC2822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher acctM = ACCT.matcher(body);
        Matcher dirM = DIR.matcher(body);
        if (!acctM.find() || !dirM.find()) return Optional.empty();

        String acct = acctM.group(1);
        Direction dir = "debited".equals(dirM.group(1)) ? Direction.DEBIT : Direction.CREDIT;

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        // parse the email Date header, convert to IST
        OffsetDateTime at = parseEmailDate(body);
        if (at == null) return Optional.empty();

        // extract merchant
        Matcher merchantM = MERCHANT.matcher(body);
        String merchant = merchantM.find() ? merchantM.group(1).trim() : "";

        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant,
                null, m.messageId()));
    }

    private static OffsetDateTime parseEmailDate(String body) {
        Matcher dateM = DATE_LINE.matcher(body);
        if (!dateM.find()) return null;
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(dateM.group(1).trim(), RFC2822);
            // convert whatever timezone to IST
            return zdt.withZoneSameInstant(Dates.IST).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
