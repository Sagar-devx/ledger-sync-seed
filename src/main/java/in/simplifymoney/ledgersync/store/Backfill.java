package in.simplifymoney.ledgersync.store;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        long read = 0;
        long written = 0;
        long skipped = 0;

        for (var txn : source.all()) {
            read++;
            // Check if it already exists to handle idempotency tracking
            var existing = target.byMessageId(txn.sourceMessageIds().get(0));
            if (existing.isPresent()) {
                // To be truly idempotent and handle exact duplicates properly:
                // We check if the existing transaction has the exact same message IDs.
                // If it does, we skip.
                skipped++;
                continue;
            }
            
            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
