# Methodology

This document is the source of truth for every formula in the engine. The golden tests in
`src/test/kotlin/engine/GoldenExampleTest.kt` assert that the engine reproduces the hand-worked
numbers below exactly (to 1e-10).

## Conventions

1. **Daily periodicity.** All returns are computed per trading day, then linked.
2. **Beginning-of-day cash flows (w = 1).** External cash flows are assumed to arrive at the
   start of the day and are invested at the prior day's closing prices. The sub-period return
   denominator therefore includes the full flow.
3. **Security day return is a total return:** `r_i,t = (p_t + d_t) / p_(t-1) - 1`, where `d_t`
   is the per-share dividend paid on day `t` (zero on most days). Dividends are modeled as
   immediately reinvested in the paying security at the day-`t` closing price, so portfolio
   market values and security returns stay mutually consistent.
4. **Portfolio weights are beginning-of-day weights:** security `i`'s weight on day `t` is
   `w_i,t = q_i,t * p_i,(t-1) / Σ_j q_j,t * p_j,(t-1)` — the day-`t` holding valued at the
   prior close. Because flows trade at the prior close, the denominator equals
   `MV_(t-1) + CF_t`, which makes the weight-based and market-value-based portfolio returns
   identical (proved in the worked example).
5. **Benchmark weights** in `benchmark_weights` are beginning-of-day weights for that date:
   the benchmark's day-`t` return is `Σ_i wb_i,t * rb_i,t`.

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

**Invariant (enforced in tests to 1e-10):**

```
Σ_i (A_i + S_i + I_i) = rp_total - rb_total
```

### Multi-period reporting

For date ranges, the engine computes attribution per day and reports the **arithmetic sum** of
daily effects per sector. The per-day invariant holds exactly; the summed effects equal the sum
of daily active returns, which is *not* the geometrically compounded active return. Geometric
linking of attribution effects (Cariño / Menchero smoothing) is deliberately out of scope —
see Future Work in the README.

## Contribution

Per-security contribution to a single-period portfolio return:

```
c_i = w_i * r_i,    Σ c_i = r_portfolio
```

(with `w_i` the beginning-of-day weight from Conventions §4 — this is what makes the sum exact.)

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

*Consistency check for Conventions §4:* the beginning-of-day basis (day-2 holdings at day-1
prices) is 100·10.50 + 50·19.00 + 400·5.10 + 25·40.80 = 1,050 + 950 + 2,040 + 1,020 = 5,060
= MV_begin + CF. The weight-based and MV-based returns agree.

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
