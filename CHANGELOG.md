## Planned/TODO

# 0.2.0
- Added support for Clojure and ClojureScript (with Shadow-CLJS)
- Watch points now can be deleted
- Watch points will be updated if you change the editor (this implementation is still a little buggy)
- Better support for manually defining watch expressions with some IDs
- Better autocomplete support (for Ruby and Clojure)
- Capturing error in Shadow and displaying on the build info
- Added keymaps by default

# 0.1.0
- Fixed "top block"
- Fixed `NREPL.watch!` watch point not being used
- Fixed some Ruby parsing code
- Added command "get last exception"
- Added clickable stacktraces
- Some fixes for goto definition
- Added autocomplete for `Symbol`s
- Clear watches
- UI improvements (removed breakpoints part, better sizing)

# 0.0.2

- Parsing Ruby results to render better results
- Better filter to traces
- Fix an exception error when filtering with invalid regexes
- Config to maximum traces allowed

# 0.0.1

- First version of Lazuli plug-in
