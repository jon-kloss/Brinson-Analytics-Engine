# Methodology

This document is the source of truth for every formula in the engine. The golden tests
assert that the engine reproduces the hand-worked numbers below to **1e-12**, both in pure
Kotlin (`src/test/kotlin/engine/GoldenExampleTest.kt`) and end-to-end through the SQL paths
(`src/test/kotlin/queries/GoldenSqlTest.kt`); the randomized invariant tests assert their
identities to **1e-10**.

## Conventions

1. **Daily periodicity.** All returns are computed per trading day, then linked.
2. **Beginning-of-day cash flows (w = 1).** External cash flows are assumed to arrive at the
   start of the day and are invested at the prior day's closing prices. The sub-period return
   denominator therefore includes the full flow.
3. **Security day return is a total return:** `r_i,t = (p_i,t + d_i,t) / p_i,(t-1) - 1`, where
   `d_i,t` is the per-share dividend paid by security `i` on day `t` (zero on most days).
   Dividends are modeled as immediately reinvested in the paying security at the day-`t`
   closing price, so portfolio market values and security returns stay mutually consistent.
4. **Portfolio weights are prior-close market-value weights:** security `i`'s weight on day
   `t` is `w_i,t = MV_i,(t-1) / Σ_j MV_j,(t-1)`. External flows are assumed to be invested
   pro-rata across existing holdings at the prior close (the data generator guarantees
   this), so flows scale every position equally and do not disturb weights. Under that
   assumption the weight-based portfolio return `Σ w_i,t * r_i,t` equals the
   market-value-based sub-period return `r_t` from the TWR section *exactly* — this is
   asserted as a property test. When a flow is **not** pro-rata (as in the worked example's
   day 2), the two can differ for that day; attribution always uses the weight-based
   definition, and the per-day attribution invariant is stated against it.
5. **Benchmark weights** in `benchmark_weights` are beginning-of-day weights for that date
   and are generated to sum to 1 per date. The engine nevertheless computes the benchmark's
   day-`t` return in the normalized form `Σ_i wb_i,t * rb_i,t / Σ_i wb_i,t`, so a weight set
   that does not sum to exactly 1 cannot silently skew the result.

## Time-Weighted Return (TWR)

Per portfolio, per day:

```
r_t = (MV_end - MV_begin - CF_t) / (MV_begin + w * CF_t),   w = 1 (beginning-of-day)
```

`CF_t` is the net **external** flow on day `t` (type `CASHFLOW`; positive = contribution).
Dividends are internal income, not external flows.

Geometric linking over a period of `n` days:

```
TWR = ∏ (1 + r_t) - 1
```

Annualization is applied **only** for periods ≥ 1 calendar year:

```
TWR_ann = (1 + TWR)^(365.25 / days) - 1
```

where `days` is the number of **calendar** days in the measurement period — from the close
the first sub-period return is measured against (the prior trading day's close, not "start
date minus one") through the period end. Periods of 365 or more calendar days annualize;
shorter periods are reported cumulative.

With zero cash flows every day, TWR reduces to `MV_end / MV_begin - 1` (asserted as an
invariant test).

## Brinson-Fachler Attribution (single period, by sector)

For sector `i`, with portfolio sector weight `wp_i`, benchmark sector weight `wb_i`, portfolio
sector return `rp_i`, benchmark sector return `rb_i`, and total benchmark return
`rb = Σ wb_i * rb_i`:

```
Allocation:   A_i = (wp_i - wb_i) * (rb_i - rb)
Selection:    S_i = wb_i * (rp_i - rb_i)
Interaction:  I_i = (wp_i - wb_i) * (rp_i - rb_i)
```

Sector quantities are weighted aggregates of the security-level inputs defined in
*Conventions*: `wp_i = Σ w_j` and `rp_i = Σ w_j r_j / wp_i` over securities `j` in sector `i`
(same shape for the benchmark side).

**Unheld sectors (`wp_i = 0`):** `rp_i` is then undefined; the engine defines `rp_i := rb_i`,
so `S_i = I_i = 0` and only the allocation effect remains. The invariant below is unaffected:
when `wp_i = 0`, the `rp_i` terms in `S_i` and `I_i` cancel exactly, so any finite choice of
`rp_i` preserves the identity — `rb_i` is chosen so the unheld sector *displays* zero
selection rather than a fictitious one.

**Coverage assumption:** the benchmark must span every sector the portfolio holds — the
attribution grid is built from the benchmark's (date, sector) spine, so a portfolio-only
sector would otherwise be silently dropped and break the invariant. The generator guarantees
this by giving the benchmark a nonzero weight in every security in the universe.

**Invariant (enforced in tests to 1e-10):**

```
Σ_i (A_i + S_i + I_i) = rp_total - rb_total
```

### Multi-period linking (Cariño)

Attribution is computed per day; daily effects cannot simply be added across days, because
returns compound geometrically while effects are arithmetic decompositions. The report
therefore applies **Cariño log-linking**. With daily attribution returns `rp_t = Σ wp_i rp_i`
and `rb_t` (the benchmark total), and cumulative returns `R_p = ∏(1+rp_t) − 1`,
`R_b = ∏(1+rb_t) − 1`, define

```
k(a, b) = (ln(1+a) - ln(1+b)) / (a - b),   with limit 1/(1+a) when a = b
k_t = k(rp_t, rb_t)        per-day coefficient
k   = k(R_p,  R_b)         full-period coefficient
```

Each day's effects are scaled by `k_t / k`. Because
`Σ k_t (rp_t − rb_t) = Σ [ln(1+rp_t) − ln(1+rb_t)] = ln(1+R_p) − ln(1+R_b) = k (R_p − R_b)`,
the scaled effects sum **exactly** to the geometric active return `R_p − R_b` (enforced in
tests to 1e-12 at the engine level and 1e-10 through the pipeline).

**Worked example** (reproduced by `CarinoTest`): two periods with
`(rp, rb) = (10%, 5%)` then `(−2%, +1%)`.

```
R_p = 1.10 · 0.98 − 1 = 0.0780      R_b = 1.05 · 1.01 − 1 = 0.0605
R_p − R_b = 0.0175

k_1 = (ln 1.10 − ln 1.05) / 0.05    = 0.930400312698
k_2 = (ln 0.98 − ln 1.01) / (−0.03) = 1.005101272356
k   = (ln 1.078 − ln 1.0605) / 0.0175 = 0.935255855097

factor_1 = k_1/k = 0.994808327183 → scaled active  +0.049740416359
factor_2 = k_2/k = 1.074680545305 → scaled active  −0.032240416359
                                      sum          = 0.017500000000 ✓ = R_p − R_b
```

Two notes. First, the reconciliation target is the *difference* of cumulative returns
(`R_p − R_b`), the standard Cariño convention — not the ratio form `(1+R_p)/(1+R_b) − 1`.
Second, `rp_t` here is the weight-based attribution return (Conventions §4); it equals the
TWR sub-period return exactly under the pro-rata flow assumption, so in generated data the
linked totals reconcile to the TWR active return as well.

The **benchmark query paths** (`bench`, the naive/optimized equivalence) still aggregate
raw arithmetic sums of daily effects — linking is a per-portfolio report-layer concern,
deliberately kept out of the timed aggregation workload.

## Contribution

Per-security contribution to a single-period portfolio return:

```
c_i = w_i * r_i,    Σ c_i = r_portfolio
```

(with `w_i` the prior-close market-value weight from Conventions §4 — this is what makes the
sum exact.)

---

## Worked golden example

2 sectors (Tech, Energy), 4 securities, 3 trading days, one external cash flow. All numbers
below are reproduced by `GoldenExampleTest`.

### Setup

| Security | Sector  | Day-0 qty | Day-0 price | Day-0 MV |
|----------|---------|-----------|-------------|----------|
| AAA      | Tech    | 100       | 10.00       | 1,000    |
| BBB      | Tech    | 50        | 20.00       | 1,000    |
| CCC      | Energy  | 200       | 5.00        | 1,000    |
| DDD      | Energy  | 25        | 40.00       | 1,000    |

Day-0 portfolio MV = **4,000**. Day 0 is the inception snapshot; returns start on day 1.

Closing prices:

| Security | Day 1 | Day 2 | Day 3 |
|----------|-------|-------|-------|
| AAA      | 10.50 | 11.00 | 11.11 |
| BBB      | 19.00 | 19.00 | 19.38 |
| CCC      | 5.10  | 5.25  | 5.20  |
| DDD      | 40.80 | 40.00 | 41.00 |

**Cash flow:** +1,020 arrives at the beginning of day 2 and buys 200 shares of CCC at the
prior close (5.10). Quantities are otherwise constant. No dividends in this example.

### TWR by hand

**Day 1** (no flow): MV_end = 100·10.50 + 50·19.00 + 200·5.10 + 25·40.80
= 1,050 + 950 + 1,020 + 1,020 = **4,040**.

```
r_1 = (4040 - 4000 - 0) / (4000 + 0) = 40 / 4000 = 0.01
```

**Day 2** (CF = +1,020, CCC qty now 400): MV_end = 100·11.00 + 50·19.00 + 400·5.25 + 25·40.00
= 1,100 + 950 + 2,100 + 1,000 = **5,150**.

```
r_2 = (5150 - 4040 - 1020) / (4040 + 1020) = 90 / 5060 = 0.0177865612648221...
```

*Denominator check:* the beginning-of-day invested basis (day-2 holdings at day-1 prices) is
100·10.50 + 50·19.00 + 400·5.10 + 25·40.80 = 1,050 + 950 + 2,040 + 1,020 = 5,060
= MV_begin + CF, confirming the w = 1 convention. (Note this flow buys a single security —
it is *not* pro-rata — so day 2 is a TWR illustration only; the attribution assertions below
use day 1, which has no flow.)

**Day 3** (no flow): MV_end = 100·11.11 + 50·19.38 + 400·5.20 + 25·41.00
= 1,111 + 969 + 2,080 + 1,025 = **5,185**.

```
r_3 = (5185 - 5150) / 5150 = 35 / 5150 = 0.0067961165048543...
```

**Linked:**

```
TWR = 1.01 · (1 + 9/506) · (1 + 7/1030) - 1 = 3537/101200 = 0.0349505928853754...
```

(Not annualized: the period is 3 days < 1 year.)

### Brinson-Fachler by hand (day 1)

Day-1 security returns (no dividends): AAA +5%, BBB −5%, CCC +2%, DDD +2%.
Benchmark returns per security are identical (same securities); benchmark beginning-of-day
weights on day 1:

| Security | wb    |
|----------|-------|
| AAA      | 0.45  |
| BBB      | 0.15  |
| CCC      | 0.25  |
| DDD      | 0.15  |

Portfolio beginning-of-day weights are 0.25 each (each position worth 1,000 of 4,000).

**Sector aggregates:**

| Sector | wp   | wb   | rp_i                                  | rb_i                                            |
|--------|------|------|---------------------------------------|-------------------------------------------------|
| Tech   | 0.50 | 0.60 | (0.25·5% + 0.25·(−5%)) / 0.50 = **0%** | (0.45·5% + 0.15·(−5%)) / 0.60 = 0.015/0.60 = **2.5%** |
| Energy | 0.50 | 0.40 | (0.25·2% + 0.25·2%) / 0.50 = **2%**    | (0.25·2% + 0.15·2%) / 0.40 = **2%**              |

Totals: rp = 0.50·0% + 0.50·2% = **1.0%**; rb = 0.60·2.5% + 0.40·2% = **2.3%**;
active return = 1.0% − 2.3% = **−1.3%**.

**Effects:**

Tech:
```
A = (0.50 - 0.60) · (0.025 - 0.023) = (-0.10) · ( 0.002) = -0.0002
S =          0.60 · (0.000 - 0.025) =  0.60   · (-0.025) = -0.0150
I = (0.50 - 0.60) · (0.000 - 0.025) = (-0.10) · (-0.025) = +0.0025
```

Energy:
```
A = (0.50 - 0.40) · (0.020 - 0.023) =  0.10 · (-0.003) = -0.0003
S =          0.40 · (0.020 - 0.020) =  0
I = (0.50 - 0.40) · (0.020 - 0.020) =  0
```

**Sum of all effects:** −0.0002 − 0.0150 + 0.0025 − 0.0003 = **−0.0130** = active return ✓

### Contribution by hand (day 1)

```
c_AAA = 0.25 ·  5% = +0.0125
c_BBB = 0.25 · -5% = -0.0125
c_CCC = 0.25 ·  2% = +0.0050
c_DDD = 0.25 ·  2% = +0.0050
Σ c_i = 0.0100 = r_portfolio ✓
```
