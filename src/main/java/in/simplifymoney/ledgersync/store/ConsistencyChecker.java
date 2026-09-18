package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new java.util.ArrayList<>();
        List<in.simplifymoney.ledgersync.model.NormalizedTxn> sqlAll = sql.all();

        // 1. Check transaction counts and existence
        java.util.Map<String, java.util.List<in.simplifymoney.ledgersync.model.NormalizedTxn>> sqlByMessage = new java.util.HashMap<>();
        for (var t : sqlAll) {
            String msgId = t.sourceMessageIds().get(0);
            sqlByMessage.computeIfAbsent(msgId, k -> new java.util.ArrayList<>()).add(t);
        }

        for (var entry : sqlByMessage.entrySet()) {
            String msgId = entry.getKey();
            var sqlList = entry.getValue();
            var docOpt = documents.byMessageId(msgId);
            
            if (docOpt.isEmpty()) {
                divergences.add(new Divergence("Missing in DocumentStore", msgId + " exists in SQL", "Not found"));
            } else {
                if (sqlList.size() > 1) {
                    divergences.add(new Divergence("Duplicate in SQL", "SQL has " + sqlList.size() + " copies of " + msgId, "DocStore has 1 deduplicated copy"));
                }
                
                var sqlTxn = sqlList.get(0);
                var docTxn = docOpt.get();
                if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                    divergences.add(new Divergence("Amount mismatch for " + msgId, sqlTxn.amount().toPlainString(), docTxn.amount().toPlainString()));
                }
                if (sqlTxn.category() != docTxn.category()) {
                    divergences.add(new Divergence("Category mismatch for " + msgId, sqlTxn.category().name(), docTxn.category().name()));
                }
            }
        }

        // 2. Check category totals
        java.util.Map<String, java.util.Map<in.simplifymoney.ledgersync.model.Category, java.math.BigDecimal>> sqlTotals = new java.util.HashMap<>();
        for (var t : sqlAll) {
            sqlTotals.computeIfAbsent(t.accountLast4(), k -> new java.util.EnumMap<>(in.simplifymoney.ledgersync.model.Category.class));
            var totals = sqlTotals.get(t.accountLast4());
            totals.put(t.category(), totals.getOrDefault(t.category(), java.math.BigDecimal.ZERO.setScale(2)).add(t.amount()));
        }

        for (String acct : sqlTotals.keySet()) {
            var docTotals = documents.categoryTotals(acct);
            var sTotals = sqlTotals.get(acct);
            
            for (var cat : in.simplifymoney.ledgersync.model.Category.values()) {
                var sAmt = sTotals.getOrDefault(cat, java.math.BigDecimal.ZERO.setScale(2));
                var dAmt = docTotals.getOrDefault(cat, java.math.BigDecimal.ZERO.setScale(2));
                
                // Compare rounded to 2 decimals to avoid minor floating point issues
                if (sAmt.compareTo(dAmt) != 0) {
                    // Note: If deduplication happened, totals WILL mismatch. 
                    divergences.add(new Divergence("Total mismatch for " + acct + " " + cat.name(), sAmt.toPlainString(), dAmt.toPlainString()));
                }
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
