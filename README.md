# Lazuli

Interactive development with Ruby and Clojure for the [Pulsar](https://pulsar-edit.dev/) editor!

Lazuli the evolution of
[Chlorine](https://gitlab.com/clj-editors/atom-chlorine), for the both Clojure
and Ruby languages (and possibly more in the future!). It aims to bring the
features of Chlorine to any supported language - basically, semantic
autocomplete and go to var definition using runtime information (that is, a code
that _is running_) instead of relying on static analysis that might miss most
cases, like dynamic definition of methods using `has_many`, `belongs_to`, or
even by defining routes in Rails

## Features

Lazuli connects to a nREPL server like [nrepl-lazuli for
Ruby](https://gitlab.com/clj-editors/nrepl-lazuli), or [nREPL for
Clojure](https://github.com/nrepl/nrepl). For ClojureScript, it also allows you
to connect directly to Shadow-CLJS.

It will use runtime information to evaluate code inside the editor, and also add
some  semantic information like where some function/method was defined. In Ruby,
for example, it'll try to avoid going to the first definition (which usually is
just a Gem or other library) and match the actual user's code (as it's possible
to see in the example below, where Go To Var Definition goes to `has_many` and
to the actual `routes.rb`). It also adds semantic autocomplete, meaning that it
uses runtime info to actually _run code_ to complete stuff (that might be
dangerous if one of the code is trying to remove a file, for example, so use it
with caution).

![Evaluating code](docs/eval-code.gif)

## For Ruby

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

## Usage:

Install [nrepl-lazuli](https://gitlab.com/clj-editors/nrepl-lazuli) and
configure your app to run the nREPL server (more info on the nREPL Lazuli repo).
Connect the editor to that REPL, and interact with the code you want to
evaluate, to generate traces and watch points. Then, have fun!

## Keybindings:

By default, the package register keybindings for evaluating code. CTRL+Enter will evaluate a "top-block" and SHIFT+Enter will evaluate a "block". See [docs/blocks.md](docs/blocks.md) to check what are these things.

If you have something selected, you can hit "CTRL+Enter" to evaluate only the selected text. With Ruby, this will also use the "watch points" so you can evaluate a local variable if you have a watch point with that binding saved.

### Additional bindings

Lazuli will also define `ctrl-alt-shift-down` to "goto definition", `ctrl-shit-c` to clear-console, and `ctrl-shit-i` to clear the inline results. These will be bound only if you don't use the VIM-Mode Plus package.

If you do, while in Normal Mode, these keybindings will be bound to `g f` (go to definition), `space l` to clear console, and `space space` to clear the inline results. You can change these bindings on your keymaps file, and you can add more keybindings at any time.

### Code Contributors

This project, and others exist thanks to all the people who contribute, both from this repo or from the old Chlorine one. [[Contribute](docs/developing.md)].
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
