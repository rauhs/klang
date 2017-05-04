# 0.5

- Big rewrite. Core API like `info!` etc all stayed compatible.
- Got rid of mutations inside the CLJ macros. Very bad idea.
  Configuration is now done by Java system properties or EDN files
- Got rid of reagent, and core.async dependency.
  Only requirement now is to have React loaded.
- Renamed `klang.macros` to `klang.core`.
- Should be much faster, much smaller and a *LOT* simpler.

# v0.2 - ~5/2015

- Offering (optional) macros which can:
  - capture local binding
  - add line number and filename
  - add stack trace
  - whitelist/blacklist arbitrary namespaces/types
 to each macro logging call.
- Performance improvement with pre-rendering each log message
- Firefox fixes
