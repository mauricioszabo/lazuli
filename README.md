# Lazuli

Interactive development with Ruby for the [Pulsar](https://pulsar-edit.dev/) editor!

Lazuli is a port (kind of!) of
[Chlorine](https://gitlab.com/clj-editors/atom-chlorine), for the Clojure
language. It aims to bring the features of Chlorine to the Ruby language -
basically, semantic autocomplete and go to var definition using runtime
information (that is, a code that _is running_) instead of relying on static
analysis that might miss most cases, like dynamic definition of methods using
`has_many`, `belongs_to`, or even by defining routes in Rails

## Features

Lazuli connects to [nrepl-lazuli](https://gitlab.com/clj-editors/nrepl-lazuli)
and uses runtime info to evaluate code inside the editor. It also adds some
semantic information like where some method was defined, trying to avoid going
to the first definition (which usually is just a Gem or other library) and
matching the actual user's code (as it's possible to see in the example below,
where Go To Var Definition goes to `has_many` and to the actual `routes.rb`). It
also adds semantic autocomplete, meaning that it uses runtime info to actually
_run code_ to complete stuff (that might be dangerous if one of the code is
trying to remove a file, for example, so use it with caution).

![Evaluating code](docs/eval-code.gif)

### Tracing

Lazuli traces your code to check where a method was called, and then keeps a
binding of it. You can check these traces in the "Trace" section of the "Lazuli
REPL" tab, and filter by typing parts of a filename (like `controller` for
example). The traces are shown in the order they were executed - so if you
visted a "Users List" page in Rails, it'll probably have a trace like
`users_controller.rb` > `user.rb` > `index.html.erb`.

Traces also automatically generate "Watch Points" for you

### Watch Points

Watch points can be added manually (more on that on future versions, for now
it's a little buggy) or automatically by Lazuli. One way they are generated
automatically are by tracing the code, which Lazuli does by default (it'll be
configurable in the future, it might be a performance hit in some edge cases) or
by running the command "Load File and Inspect" which will instrument the current
file and, as soon as you hit that code, it'll make the watch point for you.

Watch points are basically "saved context" (or, to use Ruby's correct terms,
`binding`s) so that you can evaluate code _inside an instance or method_. This
also adds semantic autocomplete and goto var definition (it does that by running
your code and checking results, trying to comple the last portion of your call.
So, for example, `my.object.cal` <- if you put the cursor at the end of this
word, Lazuli will evaluate `my.object`, get the result of it and call `.methods`
on it to check which methods are available for autocomplete).

Watch points are not bullet-proof - Lazuli will try to use the _closest watch
point_ it finds related to the current row/column the current editor is pointing
to. For example, suppose we have the code:

```ruby
def something(a, b)
  "#{a.upcase} - #{b.downcase}"
end

def other_thing(a, b)
  a / b
end
```

If we have a watch point in the _first method_ - that is, `something` - and we
try to evaluate something inside the _second method_ - the `other_thing` - it
will happily use the first method's watch point and we will have an unreliable
result - that is, `a` and `b` being strings instead of numbers. To fix this,
just run some code that touches `other_thing`.

### Breakpoints

Not fully correct yet, but in the future it might be possible to add
"breakpoints" - that is, a code that stops all evaluation but allows Lazuli to
keep running that specific line, making changes, up to the point we can
"release" the breakpoint inside the editor. Might be a **huge advantage** on
fixing bugs that are caused for things that need more context, for example
database transactions.

## Usage:

Install [nrepl-lazuli](https://gitlab.com/clj-editors/nrepl-lazuli) and
configure your app to run the nREPL server (more info on the nREPL Lazuli repo).
Connect the editor to that REPL, and interact with the code you want to
evaluate, to generate traces and watch points. Then, have fun!

## Keybindings:

This package does not register any keybindings (for now, at
#least) to avoid keybinding conflict issues. You can define whatever you want
#via keymap.cson. The following have worked for some people:

**If you use vim-mode-plus:**

```cson
'atom-text-editor.vim-mode-plus.normal-mode':
  'g f':          'lazuli:go-to-var-definition'
  'space l':      'lazuli:clear-console'
  'shift-enter':  'lazuli:evaluate-line'
  'ctrl-enter':   'lazuli:evaluate-top-block'
  'ctrl-c':       'lazuli:break-evaluation'
  'space space':  'lazuli:clear-inline-results'

'atom-text-editor.vim-mode-plus.insert-mode':
  'shift-enter': 'lazuli:evaluate-line'
  'ctrl-enter': 'lazuli:evaluate-top-block'
```

**If you don't use vim bindings:**

```cson
'atom-text-editor':
  'ctrl-; y':       'lazuli:connect-socket-repl'
  'ctrl-; e':       'lazuli:disconnect'
  'ctrl-; k':       'lazuli:clear-console'
  'ctrl-; b':       'lazuli:evaluate-line'
  'ctrl-; B':       'lazuli:evaluate-top-block'
  'ctrl-; s':       'lazuli:evaluate-selection'
  'ctrl-; c':       'lazuli:break-evaluation'
```

Other command you might want to add is `lazuli:go-to-var-definition`.

## Future

There might be a way to peek definitions, show documentation of functions, run
tests and other features. One thing I want to add is the ability to run tests
and capture watch expressions, keeping the interpreter open so it might be
possible, and maybe even easy, do debug failures and automatically fix them
inside the editor.

Interpretation of Ruby results in on radar - basically, when an exception
happens, show stacktraces inside the editor, or when it doesn't happen, have a
"pretty-printable" version of the result - maybe even with some introspection
features like "if the result is a class, get the methods of it to show what the
class supports"

### Code Contributors

This project, and others exist thanks to all the people who contribute. [[Contribute](docs/developing.md)].
<a href="https://github.com/mauricioszabo/atom-chlorine/graphs/contributors"><img src="https://opencollective.com/atom-chlorine/contributors.svg?width=890&button=false" /></a>

Please notice that the contributions mention Chlorine. Both Lazuli and Chlorine
share more than 80% of the code, so contributing to one will contribute to both.

### Financial Contributors

Become a financial contributor and help us sustain our community. Contribute:

<a href="https://opencollective.com/atom-chlorine">OpenCollective: <img src="https://opencollective.com/atom-chlorine/tiers/backers.svg?avatarHeight=60&width=800"></a>

<a href="https://www.patreon.com/bePatron?u=34618740">Patreon: <img alt="become a patron" src="https://c5.patreon.com/external/logo/become_a_patron_button.png" height="35px" class="patreon"></a>

[Or via PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=GNVSYLBPP2HGY&currency_code=USD)


#### Individuals

<a href="https://opencollective.com/atom-chlorine"><img src="https://opencollective.com/atom-chlorine/individuals.svg?width=890"></a>


#### Organizations

Support this project with your organization. Your logo will show up here with a link to your website. [[Contribute](https://opencollective.com/atom-chlorine/contribute)]

<a href="https://opencollective.com/atom-chlorine/organization/0/website"><img src="https://opencollective.com/atom-chlorine/organization/0/avatar.svg"></a>
