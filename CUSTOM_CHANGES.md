# Custom changes backup

This repository holds the custom delta of a Material Files fork.

- Base: zhanghai/MaterialFiles @ 93c3339 (public upstream repository)
- The files here are exactly the files added or modified relative to
  that base commit, stored at their original paths. To reconstruct the
  full source tree, clone the upstream repository at the base commit
  and copy these files over it.
- Not included (by design): the signing keystore and signing
  properties (secrets, kept locally only), machine-local properties,
  build outputs, and binary assets (all binaries are upstream's own
  and ship with the upstream repository; every custom addition is
  text: Kotlin sources, XML layouts/drawables/menus, strings).
- The remote README.md is the upstream project README, pushed
  alongside this delta.
