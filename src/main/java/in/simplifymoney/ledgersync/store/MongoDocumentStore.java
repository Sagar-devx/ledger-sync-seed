package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.MongoWriteException;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MongoDocumentStore implements DocumentStore {

    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore(String uri) {
        this.client = MongoClients.create(uri);
        this.db = client.getDatabase("ledgersync");
        this.txns = db.getCollection("transactions");
        this.totals = db.getCollection("category_totals");

        // Q1 index
        txns.createIndex(Indexes.descending("accountLast4", "occurredAt"));
        // Q3 index
        txns.createIndex(Indexes.ascending("sourceMessageIds"));
    }

    public void close() {
        client.close();
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        Date start = Date.from(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        Date end = Date.from(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        List<NormalizedTxn> res = new ArrayList<>();
        for (Document d : txns.find(
                Filters.and(
                        Filters.eq("accountLast4", accountLast4),
                        Filters.gte("occurredAt", start),
                        Filters.lt("occurredAt", end)
                )
        ).sort(Sorts.descending("occurredAt"))) {
            res.add(fromDoc(d));
        }
        return res;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            out.put(c, BigDecimal.ZERO.setScale(2));
        }

        for (Document d : totals.find(Filters.eq("accountLast4", accountLast4))) {
            Category c = Category.valueOf(d.getString("category"));
            BigDecimal amt = d.get("total", Decimal128.class).bigDecimalValue();
            out.put(c, amt);
        }
        return out;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = txns.find(Filters.eq("sourceMessageIds", messageId)).first();
        if (d == null) return Optional.empty();
        return Optional.of(fromDoc(d));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String hash = computeHash(txn);
        Document d = toDoc(txn);
        d.put("_id", hash);

        try {
            txns.insertOne(d);
            
            // Only increment totals if insert succeeded (not a duplicate)
            totals.updateOne(
                    Filters.and(
                            Filters.eq("accountLast4", txn.accountLast4()),
                            Filters.eq("category", txn.category().name())
                    ),
                    Updates.inc("total", new Decimal128(txn.amount())),
                    new UpdateOptions().upsert(true)
            );
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) { // duplicate key
                throw e;
            }
        }
    }

    private Document toDoc(NormalizedTxn t) {
        return new Document()
                .append("accountLast4", t.accountLast4())
                .append("occurredAt", Date.from(t.occurredAt().toInstant()))
                .append("direction", t.direction().name())
                .append("amount", new Decimal128(t.amount()))
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("sourceMessageIds", t.sourceMessageIds());
    }

    private NormalizedTxn fromDoc(Document d) {
        return new NormalizedTxn(
                d.getString("accountLast4"),
                d.getDate("occurredAt").toInstant().atOffset(ZoneOffset.UTC),
                Direction.valueOf(d.getString("direction")),
                d.get("amount", Decimal128.class).bigDecimalValue(),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                d.getList("sourceMessageIds", String.class)
        );
    }

    private String computeHash(NormalizedTxn t) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String key = t.accountLast4() + "|" 
                       + t.occurredAt().toEpochSecond() + "|" 
                       + t.direction() + "|" 
                       + t.amount().toPlainString() + "|"
                       + t.category() + "|"
                       + t.merchant() + "|"
                       + String.join(",", t.sourceMessageIds());
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
