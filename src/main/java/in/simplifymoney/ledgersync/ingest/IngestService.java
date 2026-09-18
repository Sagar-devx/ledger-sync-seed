package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Handles deduplication (cross-channel SMS+email, replay bursts),
 * MICRO classification (UPI debits <= 100), and TRANSFER detection
 * (self-transfers between the user's own accounts).
 */
public final class IngestService {

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100");

    // account holder name for detecting self-transfers
    private static final String ACCOUNT_HOLDER = "PARAG KAPOOR";

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        // Step 1: parse all messages
        List<ParsedEntry> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsed.add(new ParsedEntry(p.get(), m.messageId()));
        }

        // Step 2: group by fingerprint to deduplicate
        Map<String, List<ParsedEntry>> grouped = new LinkedHashMap<>();
        for (ParsedEntry e : parsed) {
            String fp = fingerprint(e.txn);
            grouped.computeIfAbsent(fp, k -> new ArrayList<>()).add(e);
        }

        // Step 3: build one NormalizedTxn per unique fingerprint
        int written = 0;
        for (var entry : grouped.entrySet()) {
            List<ParsedEntry> entries = entry.getValue();
            ParsedTxn representative = entries.get(0).txn;

            // collect all message IDs and sort
            List<String> messageIds = new ArrayList<>();
            for (ParsedEntry e : entries) {
                messageIds.add(e.messageId);
            }
            Collections.sort(messageIds);

            Category category = classify(representative);
            NormalizedTxn txn = new NormalizedTxn(
                    representative.accountLast4(),
                    representative.occurredAt(),
                    representative.direction(),
                    representative.amount(),
                    category,
                    representative.merchant(),
                    messageIds);
            store.save(txn);
            written++;
        }

        return new Stats(messages.size(), written, skipped);
    }

    /**
     * Content-based fingerprint for deduplication.
     * Same account + amount + direction + date (to minute) = same transaction.
     */
    private String fingerprint(ParsedTxn p) {
        return p.accountLast4() + "|"
                + p.amount().toPlainString() + "|"
                + p.direction() + "|"
                + p.occurredAt().toLocalDate() + "T"
                + p.occurredAt().toLocalTime().truncatedTo(ChronoUnit.MINUTES) + "|"
                + (p.merchant() != null ? p.merchant().trim().toUpperCase() : "");
    }

    private Category classify(ParsedTxn p) {
        if (isTransfer(p)) return Category.TRANSFER;
        if (p.direction() == Direction.DEBIT && isMicro(p)) return Category.MICRO;
        if (p.direction() == Direction.DEBIT) return Category.SPEND;
        return Category.INCOME;
    }

    private boolean isMicro(ParsedTxn p) {
        return p.amount().compareTo(MICRO_THRESHOLD) <= 0
                && p.merchant() != null
                && p.merchant().toUpperCase().startsWith("UPI");
    }

    private boolean isTransfer(ParsedTxn p) {
        String m = p.merchant() == null ? "" : p.merchant().toUpperCase();
        return m.contains("IMPS") && m.contains(ACCOUNT_HOLDER);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private record ParsedEntry(ParsedTxn txn, String messageId) {
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
    }
}
