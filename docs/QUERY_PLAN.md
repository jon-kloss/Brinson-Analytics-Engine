# Query plan: optimized attribution (EXPLAIN ANALYZE)

Captured with `brinson bench --explain` against the full-scale database
(10,377,750 position rows, full 504-day range, all 50 portfolios). Profiling
instrumentation adds overhead — the README's timed medians (1.33 s) come from
warm, uninstrumented runs.

## How to read it

The plan confirms the design intent — one pass over the fact table, with row
counts collapsing as early as the math allows:

1. **`TABLE_SCAN positions_daily` → 10,377,750 rows.** One sequential scan of
   the fact table; the `date <= period end` predicate is pushed into the scan.
2. **`WINDOW` (10.38M rows).** The `lag(market_value)` that produces each
   holding's prior-close weight basis, hash-partitioned by
   (portfolio, security). This is the heaviest operator, and it runs *inside*
   the columnar engine instead of in JVM hash maps — the entire difference
   between the naive and optimized paths in one operator.
3. **`HASH_JOIN` with `benchmark_returns` (10.36M rows).** Attaches each
   holding-day's total return; the small build sides (252,000 return rows,
   500 securities) are hashed, the fact stream probes.
4. **First `HASH_GROUP_BY`: 10.36M → 277,200 rows.** The big collapse, to
   (portfolio × date × sector). Everything after this point is trivially small.
5. **Benchmark side (right branch): 252,000 → 5,544 rows** (date × sector),
   windowed for the per-date benchmark total, then `CROSS_PRODUCT` with the 50
   portfolios back to 277,200 spine rows.
6. **Final `HASH_GROUP_BY` → 550 rows** (50 portfolios × 11 sectors) — all the
   JVM ever sees.

The naive path materializes step 1 across the JDBC boundary (10.4M rows
serialized, deserialized, and hashed row-at-a-time on the heap); the optimized
path ships only step 6.

## Full plan

```
││               Total Time: 3.82s              ││
│└──────────────────────────────────────────────┘│
└────────────────────────────────────────────────┘
┌───────────────────────────┐
│           QUERY           │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│      EXPLAIN_ANALYZE      │
│    ────────────────────   │
│           0 Rows          │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│__internal_decompress_integ│
│     ral_integer(#0, 1)    │
│             #1            │
│             #2            │
│             #3            │
│             #4            │
│             #5            │
│             #6            │
│                           │
│          550 Rows         │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│          ORDER_BY         │
│    ────────────────────   │
│   daily.portfolio_id ASC  │
│      daily.sector ASC     │
│                           │
│          550 Rows         │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│__internal_compress_integra│
│     l_utinyint(#0, 1)     │
│             #1            │
│             #2            │
│             #3            │
│             #4            │
│             #5            │
│             #6            │
│                           │
│          550 Rows         │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│__internal_decompress_integ│
│     ral_integer(#0, 1)    │
│             #1            │
│             #2            │
│             #3            │
│             #4            │
│             #5            │
│             #6            │
│                           │
│          550 Rows         │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│       HASH_GROUP_BY       │
│    ────────────────────   │
│          Groups:          │
│             #0            │
│             #1            │
│                           │
│        Aggregates:        │
│          avg(#2)          │
│          avg(#3)          │
│          sum(#4)          │
│          sum(#5)          │
│          sum(#6)          │
│                           │
│          550 Rows         │
│          (0.03s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│        portfolio_id       │
│           sector          │
│             wp            │
│             wb            │
│         allocation        │
│         selection         │
│        interaction        │
│                           │
│        277200 Rows        │
│          (0.01s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│__internal_compress_integra│
│     l_utinyint(#0, 1)     │
│             #1            │
│             #2            │
│             #3            │
│             #4            │
│             #5            │
│             #6            │
│                           │
│        277200 Rows        │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│        portfolio_id       │
│           sector          │
│             wp            │
│             wb            │
│         allocation        │
│         selection         │
│        interaction        │
│                           │
│        277200 Rows        │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│        portfolio_id       │
│           sector          │
│             wp            │
│             wb            │
│         (#2 - wb)         │
│            rb_i           │
│          rb_total         │
│(COALESCE(rp, rb_i) - rb_i)│
│                           │
│        277200 Rows        │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│        portfolio_id       │
│           sector          │
│             wp            │
│             wb            │
│            rb_i           │
│          rb_total         │
│             rp            │
│                           │
│        277200 Rows        │
│          (0.00s)          │
└─────────────┬─────────────┘
┌─────────────┴─────────────┐
│         HASH_JOIN         │
│    ────────────────────   │
│      Join Type: RIGHT     │
│                           │
│        Conditions:        │
│portfolio_id = portfolio_id├────────────────────────────────────────────────────────────────────────┐
│        date = date        │                                                                        │
│      sector = sector      │                                                                        │
│                           │                                                                        │
│        277200 Rows        │                                                                        │
│          (0.14s)          │                                                                        │
└─────────────┬─────────────┘                                                                        │
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         PROJECTION        │                                                          │         PROJECTION        │
│    ────────────────────   │                                                          │    ────────────────────   │
│        portfolio_id       │                                                          │        portfolio_id       │
│            date           │                                                          │            date           │
│           sector          │                                                          │           sector          │
│             wp            │                                                          │             wb            │
│             rp            │                                                          │            rb_i           │
│                           │                                                          │          rb_total         │
│                           │                                                          │                           │
│        277200 Rows        │                                                          │        277200 Rows        │
│          (0.00s)          │                                                          │          (0.00s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         PROJECTION        │                                                          │       CROSS_PRODUCT       │
│    ────────────────────   │                                                          │    ────────────────────   │
│             #0            │                                                          │                           │
│             #1            │                                                          │                           │
│             #2            │                                                          │                           │
│             #3            │                                                          │                           ├────────────────────────────────────────────────────────────────────────┐
│             #4            │                                                          │                           │                                                                        │
│             #5            │                                                          │                           │                                                                        │
│                           │                                                          │                           │                                                                        │
│        277200 Rows        │                                                          │        277200 Rows        │                                                                        │
│          (0.00s)          │                                                          │          (0.00s)          │                                                                        │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘                                                                        │
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│           WINDOW          │                                                          │         PROJECTION        │                                                          │         TABLE_SCAN        │
│    ────────────────────   │                                                          │    ────────────────────   │                                                          │    ────────────────────   │
│        Projections:       │                                                          │            date           │                                                          │     Table: portfolios     │
│ sum(basis) OVER (PARTITION│                                                          │           sector          │                                                          │   Type: Sequential Scan   │
│   BY portfolio_id, date)  │                                                          │             wb            │                                                          │                           │
│                           │                                                          │            rb_i           │                                                          │        Projections:       │
│                           │                                                          │          rb_total         │                                                          │        portfolio_id       │
│                           │                                                          │                           │                                                          │                           │
│        277200 Rows        │                                                          │         5544 Rows         │                                                          │          50 Rows          │
│          (0.18s)          │                                                          │          (0.00s)          │                                                          │          (0.00s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘                                                          └───────────────────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         PROJECTION        │                                                          │         PROJECTION        │
│    ────────────────────   │                                                          │    ────────────────────   │
│__internal_decompress_integ│                                                          │             #0            │
│     ral_integer(#0, 1)    │                                                          │             #1            │
│             #1            │                                                          │             #2            │
│             #2            │                                                          │             #3            │
│             #3            │                                                          │             #4            │
│             #4            │                                                          │             #5            │
│                           │                                                          │                           │
│        277200 Rows        │                                                          │         5544 Rows         │
│          (0.00s)          │                                                          │          (0.00s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│       HASH_GROUP_BY       │                                                          │           WINDOW          │
│    ────────────────────   │                                                          │    ────────────────────   │
│          Groups:          │                                                          │        Projections:       │
│             #0            │                                                          │   sum((wb * rb_i)) OVER   │
│             #1            │                                                          │    (PARTITION BY date)    │
│             #2            │                                                          │ sum(wb) OVER (PARTITION BY│
│                           │                                                          │            date)          │
│        Aggregates:        │                                                          │                           │
│          sum(#3)          │                                                          │                           │
│          sum(#4)          │                                                          │                           │
│                           │                                                          │                           │
│        277200 Rows        │                                                          │         5544 Rows         │
│          (2.90s)          │                                                          │          (0.00s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         PROJECTION        │                                                          │         PROJECTION        │
│    ────────────────────   │                                                          │    ────────────────────   │
│        portfolio_id       │                                                          │             #0            │
│            date           │                                                          │             #1            │
│           sector          │                                                          │             wb            │
│           basis           │                                                          │            rb_i           │
│       (basis * ret)       │                                                          │                           │
│                           │                                                          │                           │
│       10357200 Rows       │                                                          │         5544 Rows         │
│          (0.10s)          │                                                          │          (0.00s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         PROJECTION        │                                                          │       HASH_GROUP_BY       │
│    ────────────────────   │                                                          │    ────────────────────   │
│__internal_compress_integra│                                                          │          Groups:          │
│     l_utinyint(#0, 1)     │                                                          │             #0            │
│             #1            │                                                          │             #1            │
│             #2            │                                                          │                           │
│             #4            │                                                          │        Aggregates:        │
│             #3            │                                                          │          sum(#2)          │
│                           │                                                          │          sum(#3)          │
│                           │                                                          │                           │
│       10357200 Rows       │                                                          │         5544 Rows         │
│          (0.03s)          │                                                          │          (0.01s)          │
└─────────────┬─────────────┘                                                          └─────────────┬─────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐
│         HASH_JOIN         │                                                          │         PROJECTION        │
│    ────────────────────   │                                                          │    ────────────────────   │
│      Join Type: INNER     │                                                          │            date           │
│                           │                                                          │           sector          │
│        Conditions:        │                                                          │           weight          │
│        date = date        ├──────────────┐                                           │       (weight * ret)      │
│ security_id = security_id │              │                                           │                           │
│                           │              │                                           │                           │
│       10357200 Rows       │              │                                           │        252000 Rows        │
│          (1.22s)          │              │                                           │          (0.00s)          │
└─────────────┬─────────────┘              │                                           └─────────────┬─────────────┘
┌─────────────┴─────────────┐┌─────────────┴─────────────┐                             ┌─────────────┴─────────────┐
│         PROJECTION        ││         HASH_JOIN         │                             │         HASH_JOIN         │
│    ────────────────────   ││    ────────────────────   │                             │    ────────────────────   │
│        portfolio_id       ││      Join Type: INNER     │                             │      Join Type: INNER     │
│        security_id        ││                           │                             │                           │
│            date           ││        Conditions:        │                             │        Conditions:        │
│           basis           ││ security_id = security_id ├──────────────┐              │        date = date        ├───────────────────────────────────────────┐
│                           ││                           │              │              │ security_id = security_id │                                           │
│                           ││                           │              │              │                           │                                           │
│       10357200 Rows       ││        252000 Rows        │              │              │        252000 Rows        │                                           │
│          (0.01s)          ││          (0.00s)          │              │              │          (0.06s)          │                                           │
└─────────────┬─────────────┘└─────────────┬─────────────┘              │              └─────────────┬─────────────┘                                           │
┌─────────────┴─────────────┐┌─────────────┴─────────────┐┌─────────────┴─────────────┐┌─────────────┴─────────────┐                             ┌─────────────┴─────────────┐
│         PROJECTION        ││         TABLE_SCAN        ││         TABLE_SCAN        ││         HASH_JOIN         │                             │         TABLE_SCAN        │
│    ────────────────────   ││    ────────────────────   ││    ────────────────────   ││    ────────────────────   │                             │    ────────────────────   │
│             #0            ││           Table:          ││     Table: securities     ││      Join Type: INNER     │                             │           Table:          │
│             #1            ││     benchmark_returns     ││   Type: Sequential Scan   ││                           │                             │     benchmark_weights     │
│             #2            ││                           ││                           ││        Conditions:        │                             │                           │
│             #4            ││   Type: Sequential Scan   ││        Projections:       ││ security_id = security_id │                             │   Type: Sequential Scan   │
│                           ││                           ││        security_id        ││                           │                             │                           │
│                           ││        Projections:       ││           sector          ││                           ├──────────────┐              │        Projections:       │
│                           ││            date           ││                           ││                           │              │              │        security_id        │
│                           ││        security_id        ││                           ││                           │              │              │            date           │
│                           ││            ret            ││                           ││                           │              │              │           weight          │
│                           ││                           ││                           ││                           │              │              │                           │
│       10357200 Rows       ││        252000 Rows        ││          500 Rows         ││        252000 Rows        │              │              │        252000 Rows        │
│          (0.01s)          ││          (0.01s)          ││          (0.00s)          ││          (0.00s)          │              │              │          (0.02s)          │
└─────────────┬─────────────┘└───────────────────────────┘└───────────────────────────┘└─────────────┬─────────────┘              │              └───────────────────────────┘
┌─────────────┴─────────────┐                                                          ┌─────────────┴─────────────┐┌─────────────┴─────────────┐
│           FILTER          │                                                          │         TABLE_SCAN        ││         TABLE_SCAN        │
│    ────────────────────   │                                                          │    ────────────────────   ││    ────────────────────   │
│  ((date >= '2024-01-03':  │                                                          │           Table:          ││     Table: securities     │
│  :DATE) AND (basis IS NOT │                                                          │     benchmark_returns     ││   Type: Sequential Scan   │
│           NULL))          │                                                          │                           ││                           │
│                           │                                                          │   Type: Sequential Scan   ││        Projections:       │
│                           │                                                          │                           ││        security_id        │
│                           │                                                          │        Projections:       ││           sector          │
│                           │                                                          │            date           ││                           │
│                           │                                                          │        security_id        ││                           │
│                           │                                                          │            ret            ││                           │
│                           │                                                          │                           ││                           │
│       10357200 Rows       │                                                          │        252000 Rows        ││          500 Rows         │
│          (0.09s)          │                                                          │          (0.00s)          ││          (0.00s)          │
└─────────────┬─────────────┘                                                          └───────────────────────────┘└───────────────────────────┘
┌─────────────┴─────────────┐
│         PROJECTION        │
│    ────────────────────   │
│             #0            │
```
