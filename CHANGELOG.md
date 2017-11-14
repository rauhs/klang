# 0.5.13
- Allow autofocus to switch off.

# 0.5.11
- Tiny bugfix

# 0.5.10
- Don't require js/React present during klang.core namespace load

# 0.5.9
- Automatically select search input on overlay show. (Thanks @PEZ)

# 0.5.8
- ESC closes overlay. (Thanks @PEZ)

# 0.5.5

- Trace now works properly. Will jump to the CLJS source-mapped files. 
  Enabled by default now.

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
