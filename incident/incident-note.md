1. WHAT BROKE: Amounts regex required .XX decimals — integer amounts like Rs.5 were skipped, picking up Available Balance instead.
2. HOW FOUND: Traced app.log — amount_extracted=92213.10 for a Rs.5 SMS. Regex needs \\.[0-9]{2} which fails on "Rs.5".
3. WHO AFFECTED: Every transaction with a whole-number amount — either inflated to Avl Bal or silently dropped.
4. FIX: Made decimal part optional in regex, added RoundingMode to toDecimal().
5. NON-RECURRENCE: Added tests for Rs.5, INR 18,000 etc — CI catches regressions.
