# PR Pilot Diagrams

Mermaid diagrams for the current system:

- [Architecture](architecture.md) - Host, transport, engine, external-system, and persistence
  boundaries.
- [PR discovery and review-freshness sequence](pr-discovery-sequence.md) - REST discovery,
  authenticated-user GraphQL enrichment, degraded availability, and notification activation.
- [PR review generation sequence](review-generation-sequence.md) - PR selection and context loading
  through direct or chunked generation, optional supervision, validation, and result delivery.

The diagrams describe stable behavior documented in [`ARCHITECTURE.md`](../ARCHITECTURE.md).
Update them when those boundaries, PR discovery, or the review-generation path change.
