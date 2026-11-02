# Contributing

1. Fork the repository and create a branch.
2. Run `./mvnw verify` with JDK 25.
3. Sign off your commits with `git commit -s` (Developer Certificate of Origin,
   https://developercertificate.org/). PRs without `Signed-off-by` will not be accepted.
4. Open the PR describing what changed and how you tested it.

By contributing you declare that you have the right to license your contribution under the
Apache License 2.0.

## License headers

- A file that comes from Jlama keeps its original header. If you change it, the header must end with
  `Modifications Copyright <year> Bitubi Solutions` (see `conf/license-header`).
- A file you create gets the Bitubi Solutions header in `conf/license-header-new`.
- Never remove or rewrite the original copyright of a file that comes from Jlama.
