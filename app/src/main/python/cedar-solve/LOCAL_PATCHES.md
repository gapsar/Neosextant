# Local patches to vendored cedar-solve

This directory vendors [cedar-solve](https://github.com/smroid/cedar-solve)
(a tetra3 fork). The files below carry local modifications. **When
re-vendoring or upgrading, re-apply these patches** (and consider
upstreaming them).

## 1. Out-of-bounds pattern indices in `solve_from_centroids`

**File:** `tetra3/tetra3.py`, in `Tetra3.solve_from_centroids`, inside the
`if num_centroids > verification_stars_per_fov:` block (~line 1784).

**Bug:** upstream computes `pattern_centroids_inds` against the full
centroid list *before* trimming `image_centroids` to
`verification_stars_per_fov`. Indices past the trim point then index out of
bounds into `image_centroids_vectors`, crashing solves for images with more
centroids than `verification_stars_per_fov`.

**Patch (added lines):**

```python
            # Filter pattern_centroids_inds to only include indices valid for
            # the trimmed image_centroids array, otherwise indexing into
            # image_centroids_vectors will go out of bounds.
            pattern_centroids_inds = pattern_centroids_inds[
                pattern_centroids_inds < num_centroids]
            num_pattern_centroids = len(pattern_centroids_inds)
```

**Note:** `celestial_navigator.py` also caps its centroid list at
`min(75, verification_stars_per_fov)` before calling
`solve_from_centroids`, so this trim path is normally never entered — the
patch remains as defense-in-depth for other callers.
