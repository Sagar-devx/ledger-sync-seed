package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Handles both the older "Dear Customer, Acct XX... is debited with" format
 * and the newer "ICICI Bank Acct XX... Dr/Cr INR..." format rolled out
 * mid-July 2026.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    // Old format: "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22. Info: UPI/VEGETABLE VENDOR."
    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    // New format: "ICICI Bank Acct XX9075 Dr INR 245.00 on 24-Jul-2026 09:15; UPI/BIGBASKET ref no 269443596507."
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) "
                    + "(?:INR|Rs\\.?)\\s*(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?) "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?)\\s+ref no \\S+");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        // skip promos / loan ads
        if (body.contains("pre-approved Personal Loan")) return Optional.empty();

        // try V1 (old format)
        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();

            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(), Amounts.statedBalance(body),
                    m.messageId()));
        }

        // try V2 (new format)
        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount == null || at == null) return Optional.empty();

            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(), Amounts.statedBalance(body),
                    m.messageId()));
        }

        return Optional.empty();
    }
}
