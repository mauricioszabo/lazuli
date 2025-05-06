## Planned/TODO

# 0.3.0
- Fixed some bugs related to Ruby parsing results
- Added a very early support for Python
- Fixed config files autocompletion, doc, and definition for Ruby
- Fixed "double-connection" bug - Lazuli now detects you're already connected
for the language and forbids to connect again

# 0.2.2
- Config files now work with Ruby
- Fixed cases of block evaluation with Ruby that returned the wrong body to eval
- Fixed some exception when connecting nREPLs
- Clojure config is now horored to eval something
- Fixed autocomplete issue with Clojure


# 0.2.1
- Re-added config for Clojure evaluation
- Fixed some cases of Ruby's block evaluation
- Fixed other cases for Ruby's top-block evaluation (for example, generating an invalid code if you evaluated at the last `end` keyword)
- Fixed warnings in Shadow-CLJS with older versions of Shadow
- Fixed a crash when trying to connect two REPLs at the same time
- Prepared code for config files

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
