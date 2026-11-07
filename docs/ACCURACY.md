# Accuracy notes

Two questions this document answers with measurements rather than opinions:

1. How much accuracy does a stale element set cost?
2. What can be done about it, and what cannot.

All numbers below were produced by this repository's own orbital kernel, not by
an external library, on two real ISS element sets:

| | |
|---|---|
| older set | epoch day 302.56368278 |
| newer set | epoch day 310.88530624 |
| separation | 8.32 days |

Both sets are public Celestrak data. The station used for the geometry is a
mid-latitude site; the timing error below is station-independent because it is
the along-track displacement divided by the orbital speed.

Reproduce with:

```bash
./gradlew :core:domain:test --tests "*AccuracyReportTest*" -i
```

---

## 1. Staleness cost

| element age (d) | 3D separation (km) | along-track (km) | pass-time error (s) |
|---:|---:|---:|---:|
| 8.32 | 46.3 | −46.3 | −6.0 |
| 9.32 | 65.4 | −65.4 | −8.5 |
| 10.32 | 89.7 | −89.7 | −11.7 |
| 11.32 | 119.4 | −119.4 | −15.6 |
| 13.32 | 194.8 | −194.8 | −25.4 |
| 15.32 | 291.7 | −291.6 | −38.1 |
| 16.64 | 367.9 | −367.8 | −48.0 |
| 18.32 | 478.2 | −477.9 | −62.3 |
| 20.32 | 629.3 | −628.6 | −82.0 |
| 23.64 | 926.4 | −924.2 | −120.7 |
| 28.32 | 1450.6 | −1442.4 | −188.1 |
| 33.32 | 2133.6 | −2107.3 | −275.5 |
| 37.64 | 2832.9 | −2771.0 | −362.2 |

A negative value means the prediction lags the truth.

Least squares on those thirteen points in log-log space gives a power law:

```
sigma(age) = 0.0208 * age^2.726   [seconds]
```

Residual: 6.3% mean, 13.2% worst case. The shape is not arbitrary — the
along-track error of a two-body fit accumulates as the ignored drag term grows,
which is close to `age^3`, with a slightly lower exponent because drag itself
decays as the orbit drops.

Two facts follow directly:

* `sigma(age) = 10 s` at **age = 9.6 days**. That is what "ten second accuracy"
  means in terms of data freshness.
* The 48-hour automatic refresh corresponds to **0.14 s**, two orders of
  magnitude inside the budget.

## 2. Why a 30-day-old element set cannot be corrected to ten seconds

Three extrapolation schemes were tried on the same real pair. All of them fail,
and they fail in a way that is worth recording.

### 2.1 Fit a short window, extrapolate to 29 days

| fit window | 8.3 d | 12 d | 15.3 d | 22 d | 29 d |
|---|---:|---:|---:|---:|---:|
| no correction | −3 s | −13 s | −30 s | −91 s | −189 s |
| 1 day | 0 s | −7 s | −21 s | −75 s | −168 s |
| 2 days | 0 s | −8 s | −24 s | −81 s | −175 s |
| 4 days | −1 s | −11 s | −30 s | −91 s | −191 s |
| 6 days | −2 s | −15 s | −36 s | −103 s | −207 s |
| full 8.3 days | −4 s | −21 s | −44 s | −117 s | −228 s |

The best case improves 189 s to 168 s — 11%. The larger the window, the worse
the extrapolation, because a straight line fitted to a curve diverges fastest
where it is extrapolated furthest.

### 2.2 Add a quadratic term

Three parameters on a 7-day window: the quadratic coefficient lands at
−7.9e-3 rev/d² and the 29-day error becomes **−621 s**, three times worse than
doing nothing. The window is too short for the term to be identifiable, so the
fitter uses it to absorb noise and then amplifies it outside the window.

### 2.3 Fit only the observed gap

Using exactly the 8.32-day span between the two sets, the fitted drift rate
comes out with the **wrong sign** (−3.3e-4 rev/d) because the signal inside the
window is still small compared to the curvature at its ends. Extrapolation is
worse than the uncorrected prediction.

### 2.4 Conclusion

The error grows about thirtyfold over thirty days while the model can only see
seven or eight days of it: a fourfold extrapolation in time and a thirtyfold one
in magnitude. The future atmospheric density — solar activity, geomagnetic
storms — is not present in the element set and cannot be recovered from it by
any model, however fitted.

Any implementation that claims ten-second accuracy from a month-old element set
is either measuring something else or inventing data. This fork therefore does
not attempt it.

## 3. What the app does instead

`StalenessCalibration` in `core/domain/.../analysis/StalenessModel.kt` holds two
coefficients and up to 512 measured samples.

* **Training samples are free.** Whenever an element set is replaced by a newer
  one, the old set's along-track error at the new epoch is measurable exactly:
  propagate both to that instant and project the difference on the velocity.
  That is one labelled point, and the update has already touched those rows.
* **Refitting is closed-form.** `ln(seconds) = ln(k) + p * ln(age)` is linear in
  log space, so the two-parameter regression is a single pass with no iteration.
  This code runs on the data-update path, where a failure would break the update.
* **Only tracked satellites are measured.** Measuring all seventeen thousand
  elements would add two propagator constructions each to every update, for
  samples that describe nobody's pass list.
* **The model drives behaviour.** The workbench and the analysis screen show the
  predicted error next to the data age, and the automatic updater refreshes when
  the predicted error exceeds the ten-second budget, not merely when a timer
  expires.

The seed coefficients come from the thirteen measurements in section 1; after
the first few updates the device's own measurements take over.

## 4. Limits that remain

* SGP4 against a precise ephemeris is still unverified. No public precise
  ephemeris source was reachable from the build environment (JPL Horizons timed
  out, CDDIS requires an Earthdata login, the BKG mirror was months behind), so
  no absolute-accuracy claim is made anywhere in this tree.
* The staleness model is a population model with two parameters. It predicts the
  expected error for an element set of a given age; it does not predict this
  particular satellite's error, which also depends on solar activity over the
  interval.
* At ages beyond roughly two weeks the model is extrapolating the fit, and the
  figures are indicative rather than precise. That is precisely why the app uses
  them to trigger a refresh instead of using them to correct a prediction.
