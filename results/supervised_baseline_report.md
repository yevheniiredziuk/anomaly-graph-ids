# Supervised baseline + statistical significance (T22 revision)

Generated: run on alphas_with_gt.csv (76 647 (host, window)-pairs).

## Data split
- Train size: 38187 (42 positives, base rate 0.110%)
- Test  size: 38459 (70 positives, base rate 0.182%)
- Protocol: per-day 50/50 temporal split (Section 6.2.1)

## Detectors (host-window GT = source OR destination)

### Pure-α1 (θ_A = 0.225, Section 6.5.2 fine-grid-best)
- Precision = 0.0068   95% CI [0.0043, 0.0095]
- Recall    = 0.2714   95% CI [0.1714, 0.3857]
- F1        = 0.0132   95% CI [0.0084, 0.0186]

### Logistic regression on (α1, α2, α3), threshold tuned on train (θ_LR = 0.940)
- Precision = 0.0035   95% CI [0.0000, 0.0086]
- Recall    = 0.0286   95% CI [0.0000, 0.0714]
- F1        = 0.0063   95% CI [0.0000, 0.0154]
- Coefficients (logit scale):
  α1=3.948  α2=2.432  α3=-3.920  intercept=0.553

## McNemar test: pure-α1 vs logistic regression
- b (A right, B wrong) = 126
- c (A wrong, B right) = 2324
- chi² (1 dof, with continuity correction) = 1970.126
- p-value = 0

## Interpretation for paper
1. If 95% CI of the two F1 estimates overlap substantially, the detectors are
   statistically indistinguishable on this sample despite point-estimate gaps.
2. McNemar p < 0.05 indicates a statistically significant difference in error
   patterns; p >= 0.05 means we cannot reject equal error rates.
3. Whichever direction the result points to, both the pure-α1 and the
   logistic-regression supervised variant remain far below the rule-based
   PostgreSQL baseline (F1 ≈ 0.0797), so the core narrative — that the
   three-signal composition is not competitive on CICIDS2017-like star
   topologies — is not altered by adding a supervised variant.
