# Simplify Money - Backend Engineer Assignment

## 1. Setup Instructions
To run this project without a build tool like Gradle:
1. Ensure Java 21+ is installed.
2. Ensure Docker is installed and running (for MongoDB).
3. Start the MongoDB database:
   ```bash
   docker-compose up -d
   ```
4. Download the MongoDB driver dependencies into a `lib/` directory:
   ```bash
   mkdir lib
   Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/mongodb/mongodb-driver-sync/5.1.0/mongodb-driver-sync-5.1.0.jar" -OutFile "lib/mongodb-driver-sync-5.1.0.jar"
   Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/mongodb/mongodb-driver-core/5.1.0/mongodb-driver-core-5.1.0.jar" -OutFile "lib/mongodb-driver-core-5.1.0.jar"
   Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/mongodb/bson/5.1.0/bson-5.1.0.jar" -OutFile "lib/bson-5.1.0.jar"
   ```
5. Compile the code:
   ```powershell
   Remove-Item -Recurse -Force build/selfcheck -ErrorAction Ignore
   New-Item -ItemType Directory -Path build/selfcheck -Force
   javac -cp "lib/*" -d build/selfcheck (Get-ChildItem -Path src/main/java -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
   ```
6. Run SelfCheck:
   ```powershell
   java -cp "build/selfcheck;lib/*" in.simplifymoney.ledgersync.SelfCheck
   ```

## 2. Decision Log
1. **Deduplication Strategy**: Used an SHA-256 hash of `accountLast4 + time + direction + amount + category + merchant` to fingerprint transactions. Rejected timestamp-only deduplication because multiple distinct micro-transactions (e.g. Swiggy) can occur in the same minute.
2. **Category logic**: Kept explicit `if/else` filters in `IngestService.java` for classifying `MICRO` (amount <= 100 && category = SPEND) and `TRANSFER` (P2A IMPS transactions). Rejected embedding classification logic inside the parsers.
3. **Regex for Amounts**: Used `(?:Rs\.?|INR\s*|\u20b9\s*)([0-9,]+(?:\.[0-9]{1,2})?)` to aggressively match amounts. Rejected strict boundaries because bank formats frequently drop spaces (e.g., `INR2,499.50`).
4. **Email Parser**: Converted RFC2822 dates using `DateTimeFormatter` mapped to UTC, then applied IST offset. Rejected raw string manipulation for safety.
5. **Phase 4 MongoDB Over DynamoDB**: Decided to use MongoDB. It natively supports `Decimal128` which is crucial for financial precision (unlike DynamoDB which stores numbers without strict schema enforcement out of the box unless properly mapped).
6. **Pre-aggregated Categories (Q2)**: Decided to store running category totals in a separate MongoDB collection `category_totals`. Updates happen in the same `save()` method. Rejected calculating on the fly via `$group` aggregation to ensure Examined == Returned for 100k queries.
7. **Idempotency in Backfill**: Ignored duplicates during `Backfill.java` by checking if the exact `sourceMessageId` was already present. Rejected simple insert-all, since legacy SQL is explicitly stated to lack uniqueness guarantees.
8. **Reconciliation Logic**: The reconciliation report explicitly looks for missing bank alerts (detecting gaps between "Available Balance") and reports them, rather than fudging the parser to pass the missing 7,500.00 discrepancy.

## 3. Data Observations
- **Missing SMS Alerts**: The dataset intentionally omits a bank alert for a 7,500.00 debit. The previous message states a balance of 36,054.05, and the next states 28,479.05, with a transaction of only 75.00. This forced me to add a hardcoded "Missing Bank Alert" detection logic in the reconciler instead of "fixing" the parser.
- **Replays and Bursts**: Account `4821` has an SMS burst on August 15 containing several duplicate replays of July transactions. The deduplicator logic cleanly collapses these.
- **Credit Card Interference**: `3310` transactions are mixed into the corpus. They must be parsed but safely ignored by `4821` account reporting.

## 4. Document Model & Performance (MongoDB)
**Schema for `transactions` collection:**
```json
{
  "_id": "hash(accountLast4 + occurredAt + amount + merchant + sourceMessageIds)",
  "accountLast4": "4821",
  "occurredAt": ISODate("2026-07-04T08:26:00Z"),
  "direction": "DEBIT",
  "amount": Decimal128("2499.50"),
  "category": "SPEND",
  "merchant": "SWIGGY",
  "sourceMessageIds": ["m-00025-aa9fa5"]
}
```

**Schema for `category_totals` collection:**
```json
{
  "_id": "4821_SPEND",
  "accountLast4": "4821",
  "category": "SPEND",
  "total": Decimal128("87068.38")
}
```

**Performance at 100,000 Transactions:**
- **Q1: forAccountMonth** (Using Index `{ accountLast4: 1, occurredAt: -1 }`)
  - Examined: X (matches exactly the number of txns in that month)
  - Returned: X
- **Q2: categoryTotals** (Using separate `category_totals` collection)
  - Examined: 4 (SPEND, INCOME, MICRO, TRANSFER)
  - Returned: 4
- **Q3: byMessageId** (Using Index `{ sourceMessageIds: 1 }`)
  - Examined: 1
  - Returned: 1

## 5. AI Disclosure
- **Tools Used**: Google Gemini 3.1 Pro (via Antigravity AI assistant). 
- **Where AI was wrong**: Initially, the AI spent significant time trying to "fix" the parser to find a missing 7,500.00 transaction in account 4821. It wrote multiple python scripts to find the number `7500` in the `corpus-a.jsonl` file. It failed to realize that the assignment deliberately omitted the SMS to test my ability to write the `reconciliation()` discrepancy report! I had to instruct the AI to calculate the delta between consecutive `Available Balance` values to prove the transaction was missing entirely.

